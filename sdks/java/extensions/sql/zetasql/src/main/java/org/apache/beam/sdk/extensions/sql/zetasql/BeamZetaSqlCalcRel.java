/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.sql.zetasql;

import com.google.auto.value.AutoValue;
import com.google.zetasql.AnalyzerOptions;
import com.google.zetasql.PreparedExpression;
import com.google.zetasql.Value;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.extensions.sql.impl.BeamSqlPipelineOptions;
import org.apache.beam.sdk.extensions.sql.impl.QueryPlanner.QueryParameters;
import org.apache.beam.sdk.extensions.sql.impl.rel.AbstractBeamCalcRel;
import org.apache.beam.sdk.extensions.sql.impl.utils.CalciteUtils;
import org.apache.beam.sdk.extensions.sql.meta.provider.bigquery.BeamBigQuerySqlDialect;
import org.apache.beam.sdk.extensions.sql.meta.provider.bigquery.BeamSqlUnparseContext;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.plan.RelOptCluster;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.plan.RelTraitSet;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.rel.RelNode;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.rel.core.Calc;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.rel.type.RelDataType;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.rex.RexBuilder;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.rex.RexNode;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.rex.RexProgram;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.sql.SqlDialect;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.sql.SqlIdentifier;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.sql.SqlNode;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.beam.vendor.calcite.v1_20_0.org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.Duration;
import org.joda.time.Instant;

/**
 * BeamRelNode to replace {@code Project} and {@code Filter} node based on the {@code ZetaSQL}
 * expression evaluator.
 */
@Internal
@SuppressWarnings({
  "nullness" // TODO(https://issues.apache.org/jira/browse/BEAM-10402)
})
public class BeamZetaSqlCalcRel extends AbstractBeamCalcRel {

  private static final SqlDialect DIALECT = BeamBigQuerySqlDialect.DEFAULT;
  private static final int MAX_PENDING_WINDOW = 32;
  private final BeamSqlUnparseContext context;

  private static String columnName(int i) {
    return "_" + i;
  }

  public BeamZetaSqlCalcRel(
      RelOptCluster cluster, RelTraitSet traits, RelNode input, RexProgram program) {
    super(cluster, traits, input, program);
    final IntFunction<SqlNode> fn = i -> new SqlIdentifier(columnName(i), SqlParserPos.ZERO);
    context = new BeamSqlUnparseContext(fn);
  }

  @Override
  public Calc copy(RelTraitSet traitSet, RelNode input, RexProgram program) {
    return new BeamZetaSqlCalcRel(getCluster(), traitSet, input, program);
  }

  @Override
  public PTransform<PCollectionList<Row>, PCollection<Row>> buildPTransform() {
    return new Transform();
  }

  @AutoValue
  abstract static class TimestampedFuture {
    private static TimestampedFuture create(Instant t, Future<Value> f) {
      return new AutoValue_BeamZetaSqlCalcRel_TimestampedFuture(t, f);
    }

    abstract Instant timestamp();

    abstract Future<Value> future();
  }

  private class Transform extends PTransform<PCollectionList<Row>, PCollection<Row>> {
    @Override
    public PCollection<Row> expand(PCollectionList<Row> pinput) {
      Preconditions.checkArgument(
          pinput.size() == 1,
          "%s expected a single input PCollection, but received %d.",
          BeamZetaSqlCalcRel.class.getSimpleName(),
          pinput.size());
      PCollection<Row> upstream = pinput.get(0);

      final RexBuilder rexBuilder = getCluster().getRexBuilder();
      RexNode rex = rexBuilder.makeCall(SqlStdOperatorTable.ROW, getProgram().getProjectList());

      final RexNode condition = getProgram().getCondition();
      if (condition != null) {
        rex =
            rexBuilder.makeCall(
                SqlStdOperatorTable.CASE, condition, rex, rexBuilder.makeNullLiteral(getRowType()));
      }

      BeamSqlPipelineOptions options =
          pinput.getPipeline().getOptions().as(BeamSqlPipelineOptions.class);
      Schema outputSchema = CalciteUtils.toSchema(getRowType());
      CalcFn calcFn =
          new CalcFn(
              context.toSql(getProgram(), rex).toSqlString(DIALECT).getSql(),
              createNullParams(context.getNullParams()),
              upstream.getSchema(),
              outputSchema,
              options.getZetaSqlDefaultTimezone(),
              options.getVerifyRowValues());

      // validate prepared expressions
      calcFn.setup();

      return upstream.apply(ParDo.of(calcFn)).setRowSchema(outputSchema);
    }
  }

  private static Map<String, Value> createNullParams(Map<String, RelDataType> input) {
    Map<String, Value> result = new HashMap<>();
    for (Map.Entry<String, RelDataType> entry : input.entrySet()) {
      result.put(
          entry.getKey(),
          Value.createNullValue(ZetaSqlCalciteTranslationUtils.toZetaSqlType(entry.getValue())));
    }
    return result;
  }

  /**
   * {@code CalcFn} is the executor for a {@link BeamZetaSqlCalcRel} step. The implementation is
   * based on the {@code ZetaSQL} expression evaluator.
   */
  private static class CalcFn extends DoFn<Row, Row> {
    private final String sql;
    private final Map<String, Value> nullParams;
    private final Schema inputSchema;
    private final Schema outputSchema;
    private final String defaultTimezone;
    private final boolean verifyRowValues;
    private transient PreparedExpression exp;
    private transient List<Integer> referencedColumns;
    private transient PreparedExpression.Stream stream;
    private transient Map<BoundedWindow, Queue<TimestampedFuture>> pending;
    private transient @Nullable Row previousRow;
    private transient @Nullable Future<Value> previousFuture;

    CalcFn(
        String sql,
        Map<String, Value> nullParams,
        Schema inputSchema,
        Schema outputSchema,
        String defaultTimezone,
        boolean verifyRowValues) {
      this.sql = sql;
      this.nullParams = nullParams;
      this.inputSchema = inputSchema;
      this.outputSchema = outputSchema;
      this.defaultTimezone = defaultTimezone;
      this.verifyRowValues = verifyRowValues;
    }

    @Setup
    public void setup() {
      AnalyzerOptions options =
          SqlAnalyzer.getAnalyzerOptions(QueryParameters.ofNamed(nullParams), defaultTimezone);
      for (int i = 0; i < inputSchema.getFieldCount(); i++) {
        options.addExpressionColumn(
            columnName(i),
            ZetaSqlBeamTranslationUtils.toZetaSqlType(inputSchema.getField(i).getType()));
      }

      exp = new PreparedExpression(sql);
      exp.prepare(options);

      ImmutableList.Builder<Integer> columns = new ImmutableList.Builder<>();
      for (String c : exp.getReferencedColumns()) {
        columns.add(Integer.parseInt(c.substring(1)));
      }
      referencedColumns = columns.build();

      stream = exp.stream();
    }

    @StartBundle
    public void startBundle() {
      pending = new HashMap<>();
      previousRow = null;
      previousFuture = null;
    }

    @Override
    public Duration getAllowedTimestampSkew() {
      return Duration.millis(Long.MAX_VALUE);
    }

    @ProcessElement
    public void processElement(
        @Element Row row, @Timestamp Instant t, BoundedWindow w, OutputReceiver<Row> r)
        throws InterruptedException {
      final Future<Value> valueFuture;

      if (row.equals(previousRow)) {
        valueFuture = previousFuture;
      } else {
        Map<String, Value> columns = new HashMap<>();
        for (int i : referencedColumns) {
          columns.put(
              columnName(i),
              ZetaSqlBeamTranslationUtils.toZetaSqlValue(
                  row.getBaseValue(i, Object.class), inputSchema.getField(i).getType()));
        }

        valueFuture = stream.execute(columns, nullParams);
      }
      previousRow = row;

      @Nullable Queue<TimestampedFuture> pendingWindow = pending.get(w);
      if (pendingWindow == null) {
        pendingWindow = new ArrayDeque<>();
        pending.put(w, pendingWindow);
      }
      pendingWindow.add(TimestampedFuture.create(t, valueFuture));

      while ((!pendingWindow.isEmpty() && pendingWindow.element().future().isDone())
          || pendingWindow.size() > MAX_PENDING_WINDOW) {
        outputRow(pendingWindow.remove(), r);
      }
    }

    @FinishBundle
    public void finishBundle(FinishBundleContext c) throws InterruptedException {
      stream.flush();
      for (Map.Entry<BoundedWindow, Queue<TimestampedFuture>> pendingWindow : pending.entrySet()) {
        OutputReceiver<Row> rowOutputReciever =
            new OutputReceiverForFinishBundle(c, pendingWindow.getKey());
        for (TimestampedFuture timestampedFuture : pendingWindow.getValue()) {
          outputRow(timestampedFuture, rowOutputReciever);
        }
      }
    }

    // TODO(BEAM-1287): Remove this when FinishBundle has added support for an {@link
    // OutputReceiver}
    private static class OutputReceiverForFinishBundle implements OutputReceiver<Row> {

      private final FinishBundleContext c;
      private final BoundedWindow w;

      private OutputReceiverForFinishBundle(FinishBundleContext c, BoundedWindow w) {
        this.c = c;
        this.w = w;
      }

      @Override
      public void output(Row output) {
        throw new RuntimeException("Unsupported");
      }

      @Override
      public void outputWithTimestamp(Row output, Instant timestamp) {
        c.output(output, timestamp, w);
      }
    }

    private void outputRow(TimestampedFuture c, OutputReceiver<Row> r) throws InterruptedException {
      final Value v;
      try {
        v = c.future().get();
      } catch (ExecutionException e) {
        throw (RuntimeException) e.getCause();
      }
      if (!v.isNull()) {
        Row row = ZetaSqlBeamTranslationUtils.toBeamRow(v, outputSchema, verifyRowValues);
        r.outputWithTimestamp(row, c.timestamp());
      }
    }

    @Teardown
    public void teardown() {
      stream.close();
      exp.close();
    }
  }
}
