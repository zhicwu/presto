/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.metadata.TableHandle;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.block.SortOrder;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.analyzer.Analysis;
import com.facebook.presto.sql.analyzer.Field;
import com.facebook.presto.sql.analyzer.FieldOrExpression;
import com.facebook.presto.sql.analyzer.RelationType;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.DeleteNode;
import com.facebook.presto.sql.planner.plan.EnforceSingleRowNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.GroupIdNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.LimitNode;
import com.facebook.presto.sql.planner.plan.MarkDistinctNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.SemiJoinNode;
import com.facebook.presto.sql.planner.plan.SortNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode.DeleteHandle;
import com.facebook.presto.sql.planner.plan.TopNNode;
import com.facebook.presto.sql.planner.plan.ValuesNode;
import com.facebook.presto.sql.planner.plan.WindowNode;
import com.facebook.presto.sql.tree.Cast;
import com.facebook.presto.sql.tree.DefaultTraversalVisitor;
import com.facebook.presto.sql.tree.Delete;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FrameBound;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.InPredicate;
import com.facebook.presto.sql.tree.Node;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import com.facebook.presto.sql.tree.Query;
import com.facebook.presto.sql.tree.QuerySpecification;
import com.facebook.presto.sql.tree.SortItem;
import com.facebook.presto.sql.tree.SortItem.NullOrdering;
import com.facebook.presto.sql.tree.SortItem.Ordering;
import com.facebook.presto.sql.tree.SubqueryExpression;
import com.facebook.presto.sql.tree.Window;
import com.facebook.presto.sql.tree.WindowFrame;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.sql.util.AstUtils.nodeContains;
import static com.facebook.presto.type.TypeRegistry.isTypeOnlyCoercion;
import static com.facebook.presto.util.ImmutableCollectors.toImmutableList;
import static com.facebook.presto.util.ImmutableCollectors.toImmutableSet;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.Objects.requireNonNull;

class QueryPlanner
        extends DefaultTraversalVisitor<PlanBuilder, Void>
{
    private final Analysis analysis;
    private final SymbolAllocator symbolAllocator;
    private final PlanNodeIdAllocator idAllocator;
    private final Metadata metadata;
    private final Session session;
    private final Optional<Double> approximationConfidence;

    QueryPlanner(Analysis analysis, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator, Metadata metadata, Session session, Optional<Double> approximationConfidence)
    {
        requireNonNull(analysis, "analysis is null");
        requireNonNull(symbolAllocator, "symbolAllocator is null");
        requireNonNull(idAllocator, "idAllocator is null");
        requireNonNull(metadata, "metadata is null");
        requireNonNull(session, "session is null");
        requireNonNull(approximationConfidence, "approximationConfidence is null");

        this.analysis = analysis;
        this.symbolAllocator = symbolAllocator;
        this.idAllocator = idAllocator;
        this.metadata = metadata;
        this.session = session;
        this.approximationConfidence = approximationConfidence;
    }

    @Override
    protected PlanBuilder visitQuery(Query query, Void context)
    {
        PlanBuilder builder = planQueryBody(query);

        List<FieldOrExpression> orderBy = analysis.getOrderByExpressions(query);
        builder = handleSubqueries(builder, query, orderBy);
        List<FieldOrExpression> outputs = analysis.getOutputExpressions(query);
        builder = handleSubqueries(builder, query, outputs);
        builder = project(builder, Iterables.concat(orderBy, outputs));

        builder = sort(builder, query);
        builder = project(builder, analysis.getOutputExpressions(query));
        builder = limit(builder, query);

        return builder;
    }

    @Override
    protected PlanBuilder visitQuerySpecification(QuerySpecification node, Void context)
    {
        PlanBuilder builder = planFrom(node);

        builder = filter(builder, analysis.getWhere(node), node);
        builder = aggregate(builder, node);
        builder = filter(builder, analysis.getHaving(node), node);

        builder = window(builder, node);

        List<FieldOrExpression> orderBy = analysis.getOrderByExpressions(node);
        builder = handleSubqueries(builder, node, orderBy);
        List<FieldOrExpression> outputs = analysis.getOutputExpressions(node);
        builder = handleSubqueries(builder, node, outputs);
        builder = project(builder, Iterables.concat(orderBy, outputs));

        builder = distinct(builder, node, outputs, orderBy);
        builder = sort(builder, node);
        builder = project(builder, analysis.getOutputExpressions(node));
        builder = limit(builder, node);

        return builder;
    }

    private PlanBuilder planQueryBody(Query query)
    {
        RelationPlan relationPlan = new RelationPlanner(analysis, symbolAllocator, idAllocator, metadata, session)
                .process(query.getQueryBody(), null);

        TranslationMap translations = new TranslationMap(relationPlan, analysis);

        // Make field->symbol mapping from underlying relation plan available for translations
        // This makes it possible to rewrite FieldOrExpressions that reference fields from the QuerySpecification directly
        translations.setFieldMappings(relationPlan.getOutputSymbols());

        return new PlanBuilder(translations, relationPlan.getRoot(), relationPlan.getSampleWeight());
    }

    private PlanBuilder planFrom(QuerySpecification node)
    {
        RelationPlan relationPlan;

        if (node.getFrom().isPresent()) {
            relationPlan = new RelationPlanner(analysis, symbolAllocator, idAllocator, metadata, session)
                    .process(node.getFrom().get(), null);
        }
        else {
            relationPlan = planImplicitTable();
        }

        TranslationMap translations = new TranslationMap(relationPlan, analysis);

        // Make field->symbol mapping from underlying relation plan available for translations
        // This makes it possible to rewrite FieldOrExpressions that reference fields from the FROM clause directly
        translations.setFieldMappings(relationPlan.getOutputSymbols());

        return new PlanBuilder(translations, relationPlan.getRoot(), relationPlan.getSampleWeight());
    }

    public DeleteNode planDelete(Delete node)
    {
        RelationType descriptor = analysis.getOutputDescriptor(node.getTable());
        TableHandle handle = analysis.getTableHandle(node.getTable());
        ColumnHandle rowIdHandle = metadata.getUpdateRowIdColumnHandle(session, handle);
        Type rowIdType = metadata.getColumnMetadata(session, handle, rowIdHandle).getType();

        // add table columns
        ImmutableList.Builder<Symbol> outputSymbols = ImmutableList.builder();
        ImmutableMap.Builder<Symbol, ColumnHandle> columns = ImmutableMap.builder();
        ImmutableList.Builder<Field> fields = ImmutableList.builder();
        for (Field field : descriptor.getAllFields()) {
            Symbol symbol = symbolAllocator.newSymbol(field.getName().get(), field.getType());
            outputSymbols.add(symbol);
            columns.put(symbol, analysis.getColumn(field));
            fields.add(field);
        }

        // add rowId column
        Field rowIdField = Field.newUnqualified(Optional.empty(), rowIdType);
        Symbol rowIdSymbol = symbolAllocator.newSymbol("$rowId", rowIdField.getType());
        outputSymbols.add(rowIdSymbol);
        columns.put(rowIdSymbol, rowIdHandle);
        fields.add(rowIdField);

        // create table scan
        PlanNode tableScan = new TableScanNode(idAllocator.getNextId(), handle, outputSymbols.build(), columns.build(), Optional.empty(), TupleDomain.all(), null);
        RelationPlan relationPlan = new RelationPlan(tableScan, new RelationType(fields.build()), outputSymbols.build(), Optional.empty());

        TranslationMap translations = new TranslationMap(relationPlan, analysis);
        translations.setFieldMappings(relationPlan.getOutputSymbols());

        PlanBuilder builder = new PlanBuilder(translations, relationPlan.getRoot(), relationPlan.getSampleWeight());

        if (node.getWhere().isPresent()) {
            builder = filter(builder, node.getWhere().get(), node);
        }

        // create delete node
        Symbol rowId = builder.translate(new FieldOrExpression(relationPlan.getDescriptor().indexOf(rowIdField)));
        List<Symbol> outputs = ImmutableList.of(
                symbolAllocator.newSymbol("partialrows", BIGINT),
                symbolAllocator.newSymbol("fragment", VARBINARY));

        return new DeleteNode(idAllocator.getNextId(), builder.getRoot(), new DeleteHandle(handle), rowId, outputs);
    }

    private RelationPlan planImplicitTable()
    {
        List<Expression> emptyRow = ImmutableList.of();
        return new RelationPlan(
                new ValuesNode(idAllocator.getNextId(), ImmutableList.<Symbol>of(), ImmutableList.of(emptyRow)),
                new RelationType(),
                ImmutableList.<Symbol>of(),
                Optional.empty());
    }

    private PlanBuilder filter(PlanBuilder subPlan, Expression predicate, Node node)
    {
        if (predicate == null) {
            return subPlan;
        }

        // rewrite expressions which contain already handled subqueries
        Expression rewrittenBeforeSubqueries = subPlan.rewrite(predicate);
        subPlan = handleSubqueries(subPlan, rewrittenBeforeSubqueries, node);
        Expression rewrittenAfterSubqueries = subPlan.rewrite(predicate);
        return new PlanBuilder(
                subPlan.getTranslations(),
                new FilterNode(idAllocator.getNextId(), subPlan.getRoot(), rewrittenAfterSubqueries),
                subPlan.getSampleWeight());
    }

    private PlanBuilder project(PlanBuilder subPlan, Iterable<FieldOrExpression> expressions)
    {
        TranslationMap outputTranslations = new TranslationMap(subPlan.getRelationPlan(), analysis);

        ImmutableMap.Builder<Symbol, Expression> projections = ImmutableMap.builder();
        for (FieldOrExpression fieldOrExpression : expressions) {
            Symbol symbol;

            if (fieldOrExpression.isFieldReference()) {
                Field field = subPlan.getRelationPlan().getDescriptor().getFieldByIndex(fieldOrExpression.getFieldIndex());
                symbol = symbolAllocator.newSymbol(field);
            }
            else {
                Expression expression = fieldOrExpression.getExpression();
                symbol = symbolAllocator.newSymbol(expression, analysis.getType(expression));
            }

            projections.put(symbol, subPlan.rewrite(fieldOrExpression));
            outputTranslations.put(fieldOrExpression, symbol);
        }

        if (subPlan.getSampleWeight().isPresent()) {
            Symbol symbol = subPlan.getSampleWeight().get();
            projections.put(symbol, new QualifiedNameReference(symbol.toQualifiedName()));
        }

        return new PlanBuilder(outputTranslations, new ProjectNode(idAllocator.getNextId(), subPlan.getRoot(), projections.build()), subPlan.getSampleWeight());
    }

    private Map<Symbol, Expression> coerce(Iterable<? extends Expression> expressions, PlanBuilder subPlan, TranslationMap translations)
    {
        ImmutableMap.Builder<Symbol, Expression> projections = ImmutableMap.builder();

        for (Expression expression : expressions) {
            Type type = analysis.getType(expression);
            Type coercion = analysis.getCoercion(expression);
            Symbol symbol = symbolAllocator.newSymbol(expression, firstNonNull(coercion, type));
            Expression rewritten = subPlan.rewrite(expression);
            if (coercion != null) {
                rewritten = new Cast(
                        rewritten,
                        coercion.getTypeSignature().toString(),
                        false,
                        isTypeOnlyCoercion(type.getTypeSignature(), coercion.getTypeSignature()));
            }
            projections.put(symbol, rewritten);
            translations.put(expression, symbol);
        }

        return projections.build();
    }

    private PlanBuilder explicitCoercionFields(PlanBuilder subPlan, Iterable<FieldOrExpression> alreadyCoerced, Iterable<? extends Expression> uncoerced)
    {
        TranslationMap translations = new TranslationMap(subPlan.getRelationPlan(), analysis);
        ImmutableMap.Builder<Symbol, Expression> projections = ImmutableMap.builder();

        projections.putAll(coerce(uncoerced, subPlan, translations));

        for (FieldOrExpression fieldOrExpression : alreadyCoerced) {
            Symbol symbol;
            if (fieldOrExpression.isFieldReference()) {
                Field field = subPlan.getRelationPlan().getDescriptor().getFieldByIndex(fieldOrExpression.getFieldIndex());
                symbol = symbolAllocator.newSymbol(field);
            }
            else {
                symbol = symbolAllocator.newSymbol(fieldOrExpression.getExpression(), analysis.getType(fieldOrExpression.getExpression()));
            }
            Expression rewritten = subPlan.rewrite(fieldOrExpression);
            projections.put(symbol, rewritten);
            translations.put(fieldOrExpression, symbol);
        }

        return new PlanBuilder(translations, new ProjectNode(idAllocator.getNextId(), subPlan.getRoot(), projections.build()), subPlan.getSampleWeight());
    }

    private PlanBuilder explicitCoercionSymbols(PlanBuilder subPlan, Iterable<Symbol> alreadyCoerced, Iterable<? extends Expression> uncoerced)
    {
        TranslationMap translations = copyTranslations(subPlan);
        ImmutableMap.Builder<Symbol, Expression> projections = ImmutableMap.builder();

        projections.putAll(coerce(uncoerced, subPlan, translations));

        for (Symbol symbol : alreadyCoerced) {
            projections.put(symbol, new QualifiedNameReference(symbol.toQualifiedName()));
        }

        return new PlanBuilder(translations, new ProjectNode(idAllocator.getNextId(), subPlan.getRoot(), projections.build()), subPlan.getSampleWeight());
    }

    private PlanBuilder aggregate(PlanBuilder subPlan, QuerySpecification node)
    {
        List<List<FieldOrExpression>> groupingSets = analysis.getGroupingSets(node);
        if (groupingSets.isEmpty()) {
            return subPlan;
        }

        Set<FieldOrExpression> distinctGroupingColumns = groupingSets.stream()
                .flatMap(Collection::stream)
                .collect(toImmutableSet());

        List<FieldOrExpression> arguments = analysis.getAggregates(node).stream()
                .map(FunctionCall::getArguments)
                .flatMap(List::stream)
                .map(FieldOrExpression::new)
                .collect(toImmutableList());

        // 1. Pre-project all scalar inputs (arguments and non-trivial group by expressions)
        Iterable<FieldOrExpression> inputs = Iterables.concat(distinctGroupingColumns, arguments);

        subPlan = handleSubqueries(subPlan, node, inputs);

        if (!Iterables.isEmpty(inputs)) { // avoid an empty projection if the only aggregation is COUNT (which has no arguments)
            subPlan = project(subPlan, inputs);
        }

        // 2. Aggregate
        ImmutableMap.Builder<Symbol, FunctionCall> aggregationAssignments = ImmutableMap.builder();
        ImmutableMap.Builder<Symbol, Signature> functions = ImmutableMap.builder();

        // 2.a. Rewrite aggregates in terms of pre-projected inputs
        TranslationMap translations = new TranslationMap(subPlan.getRelationPlan(), analysis);
        boolean needPostProjectionCoercion = false;
        for (FunctionCall aggregate : analysis.getAggregates(node)) {
            Expression rewritten = subPlan.rewrite(aggregate);
            Symbol newSymbol = symbolAllocator.newSymbol(rewritten, analysis.getType(aggregate));

            // TODO: this is a hack, because we apply coercions to the output of expressions, rather than the arguments to expressions.
            // Therefore we can end up with this implicit cast, and have to move it into a post-projection
            if (rewritten instanceof Cast) {
                rewritten = ((Cast) rewritten).getExpression();
                needPostProjectionCoercion = true;
            }
            aggregationAssignments.put(newSymbol, (FunctionCall) rewritten);
            translations.put(aggregate, newSymbol);

            functions.put(newSymbol, analysis.getFunctionSignature(aggregate));
        }

        // 2.b. Rewrite group by expressions in terms of pre-projected inputs
        ImmutableList.Builder<List<Symbol>> groupingSetsSymbolsBuilder = ImmutableList.builder();
        ImmutableSet.Builder<Symbol> distinctGroupingSymbolsBuilder = ImmutableSet.builder();
        for (List<FieldOrExpression> groupingSet : groupingSets) {
            ImmutableList.Builder<Symbol> groupingColumns = ImmutableList.builder();
            for (FieldOrExpression fieldOrExpression : groupingSet) {
                Symbol symbol = subPlan.translate(fieldOrExpression);
                groupingColumns.add(symbol);
                distinctGroupingSymbolsBuilder.add(symbol);
                translations.put(fieldOrExpression, symbol);
            }
            groupingSetsSymbolsBuilder.add(groupingColumns.build());
        }

        // 2.c. Add a groupIdNode and groupIdSymbol if there are multiple grouping sets
        if (groupingSets.size() > 1) {
            Symbol groupIdSymbol = symbolAllocator.newSymbol("groupId", BIGINT);
            GroupIdNode groupId = new GroupIdNode(idAllocator.getNextId(), subPlan.getRoot(), subPlan.getRoot().getOutputSymbols(), groupingSetsSymbolsBuilder.build(), groupIdSymbol);
            subPlan = new PlanBuilder(subPlan.getTranslations(), groupId, subPlan.getSampleWeight());
            distinctGroupingSymbolsBuilder.add(groupIdSymbol);
        }
        List<Symbol> distinctGroupingSymbols = distinctGroupingSymbolsBuilder.build().asList();

        // 2.d. Mark distinct rows for each aggregate that has DISTINCT
        // Map from aggregate function arguments to marker symbols, so that we can reuse the markers, if two aggregates have the same argument
        Map<Set<Expression>, Symbol> argumentMarkers = new HashMap<>();
        // Map from aggregate functions to marker symbols
        Map<Symbol, Symbol> masks = new HashMap<>();
        for (FunctionCall aggregate : Iterables.filter(analysis.getAggregates(node), FunctionCall::isDistinct)) {
            Set<Expression> args = ImmutableSet.copyOf(aggregate.getArguments());
            Symbol marker = argumentMarkers.get(args);
            Symbol aggregateSymbol = translations.get(aggregate);
            if (marker == null) {
                if (args.size() == 1) {
                    marker = symbolAllocator.newSymbol(getOnlyElement(args), BOOLEAN, "distinct");
                }
                else {
                    marker = symbolAllocator.newSymbol(aggregateSymbol.getName(), BOOLEAN, "distinct");
                }
                argumentMarkers.put(args, marker);
            }

            masks.put(aggregateSymbol, marker);
        }

        for (Map.Entry<Set<Expression>, Symbol> entry : argumentMarkers.entrySet()) {
            ImmutableList.Builder<Symbol> builder = ImmutableList.builder();
            builder.addAll(distinctGroupingSymbols);
            for (Expression expression : entry.getKey()) {
                builder.add(subPlan.translate(expression));
            }
            MarkDistinctNode markDistinct = new MarkDistinctNode(idAllocator.getNextId(),
                    subPlan.getRoot(),
                    entry.getValue(),
                    builder.build(),
                    Optional.empty());
            subPlan = new PlanBuilder(subPlan.getTranslations(), markDistinct, subPlan.getSampleWeight());
        }

        double confidence = approximationConfidence.orElse(1.0);

        AggregationNode aggregationNode = new AggregationNode(
                idAllocator.getNextId(),
                subPlan.getRoot(),
                distinctGroupingSymbols,
                aggregationAssignments.build(),
                functions.build(),
                masks,
                AggregationNode.Step.SINGLE,
                subPlan.getSampleWeight(),
                confidence,
                Optional.empty());

        subPlan = new PlanBuilder(translations, aggregationNode, Optional.empty());

        // 3. Post-projection
        // Add back the implicit casts that we removed in 2.a
        // TODO: this is a hack, we should change type coercions to coerce the inputs to functions/operators instead of coercing the output
        if (needPostProjectionCoercion) {
            return explicitCoercionFields(subPlan, distinctGroupingColumns, analysis.getAggregates(node));
        }
        return subPlan;
    }

    private PlanBuilder window(PlanBuilder subPlan, QuerySpecification node)
    {
        Set<FunctionCall> windowFunctions = ImmutableSet.copyOf(analysis.getWindowFunctions(node));
        if (windowFunctions.isEmpty()) {
            return subPlan;
        }

        for (FunctionCall windowFunction : windowFunctions) {
            Window window = windowFunction.getWindow().get();

            // Extract frame
            WindowFrame.Type frameType = WindowFrame.Type.RANGE;
            FrameBound.Type frameStartType = FrameBound.Type.UNBOUNDED_PRECEDING;
            FrameBound.Type frameEndType = FrameBound.Type.CURRENT_ROW;
            Expression frameStart = null;
            Expression frameEnd = null;

            if (window.getFrame().isPresent()) {
                WindowFrame frame = window.getFrame().get();
                frameType = frame.getType();

                frameStartType = frame.getStart().getType();
                frameStart = frame.getStart().getValue().orElse(null);

                if (frame.getEnd().isPresent()) {
                    frameEndType = frame.getEnd().get().getType();
                    frameEnd = frame.getEnd().get().getValue().orElse(null);
                }
            }

            // Pre-project inputs
            ImmutableList.Builder<Expression> inputs = ImmutableList.<Expression>builder()
                    .addAll(windowFunction.getArguments())
                    .addAll(window.getPartitionBy())
                    .addAll(Iterables.transform(window.getOrderBy(), SortItem::getSortKey));

            if (frameStart != null) {
                inputs.add(frameStart);
            }
            if (frameEnd != null) {
                inputs.add(frameEnd);
            }

            subPlan = appendProjections(subPlan, inputs.build());

            // Rewrite PARTITION BY in terms of pre-projected inputs
            ImmutableList.Builder<Symbol> partitionBySymbols = ImmutableList.builder();
            for (Expression expression : window.getPartitionBy()) {
                partitionBySymbols.add(subPlan.translate(expression));
            }

            // Rewrite ORDER BY in terms of pre-projected inputs
            Map<Symbol, SortOrder> orderings = new LinkedHashMap<>();
            for (SortItem item : window.getOrderBy()) {
                Symbol symbol = subPlan.translate(item.getSortKey());
                orderings.put(symbol, toSortOrder(item));
            }

            // Rewrite frame bounds in terms of pre-projected inputs
            Optional<Symbol> frameStartSymbol = Optional.empty();
            Optional<Symbol> frameEndSymbol = Optional.empty();
            if (frameStart != null) {
                frameStartSymbol = Optional.of(subPlan.translate(frameStart));
            }
            if (frameEnd != null) {
                frameEndSymbol = Optional.of(subPlan.translate(frameEnd));
            }

            WindowNode.Frame frame = new WindowNode.Frame(frameType,
                    frameStartType, frameStartSymbol,
                    frameEndType, frameEndSymbol);

            TranslationMap outputTranslations = copyTranslations(subPlan);

            ImmutableMap.Builder<Symbol, FunctionCall> assignments = ImmutableMap.builder();
            Map<Symbol, Signature> signatures = new HashMap<>();

            // Rewrite function call in terms of pre-projected inputs
            Expression rewritten = subPlan.rewrite(windowFunction);
            Symbol newSymbol = symbolAllocator.newSymbol(rewritten, analysis.getType(windowFunction));

            boolean needCoercion = rewritten instanceof Cast;
            // Strip out the cast and add it back as a post-projection
            if (rewritten instanceof Cast) {
                rewritten = ((Cast) rewritten).getExpression();
            }
            assignments.put(newSymbol, (FunctionCall) rewritten);
            outputTranslations.put(windowFunction, newSymbol);

            signatures.put(newSymbol, analysis.getFunctionSignature(windowFunction));

            List<Symbol> sourceSymbols = subPlan.getRoot().getOutputSymbols();
            ImmutableList.Builder<Symbol> orderBySymbols = ImmutableList.builder();
            orderBySymbols.addAll(orderings.keySet());

            // create window node
            subPlan = new PlanBuilder(outputTranslations,
                    new WindowNode(
                            idAllocator.getNextId(),
                            subPlan.getRoot(),
                            partitionBySymbols.build(),
                            orderBySymbols.build(),
                            orderings,
                            frame,
                            assignments.build(),
                            signatures,
                            Optional.empty(),
                            ImmutableSet.of(),
                            0),
                    subPlan.getSampleWeight());

            if (needCoercion) {
                subPlan = explicitCoercionSymbols(subPlan, sourceSymbols, ImmutableList.of(windowFunction));
            }
        }

        return subPlan;
    }

    private PlanBuilder appendProjections(PlanBuilder subPlan, Iterable<Expression> expressions)
    {
        TranslationMap translations = copyTranslations(subPlan);

        ImmutableMap.Builder<Symbol, Expression> projections = ImmutableMap.builder();

        // add an identity projection for underlying plan
        for (Symbol symbol : subPlan.getRoot().getOutputSymbols()) {
            Expression expression = new QualifiedNameReference(symbol.toQualifiedName());
            projections.put(symbol, expression);
        }

        ImmutableMap.Builder<Symbol, Expression> newTranslations = ImmutableMap.builder();
        for (Expression expression : expressions) {
            Symbol symbol = symbolAllocator.newSymbol(expression, analysis.getType(expression));

            projections.put(symbol, translations.rewrite(expression));
            newTranslations.put(symbol, expression);
        }
        // Now append the new translations into the TranslationMap
        for (Map.Entry<Symbol, Expression> entry : newTranslations.build().entrySet()) {
            translations.put(entry.getValue(), entry.getKey());
        }

        return new PlanBuilder(translations, new ProjectNode(idAllocator.getNextId(), subPlan.getRoot(), projections.build()), subPlan.getSampleWeight());
    }

    private PlanBuilder handleSubqueries(PlanBuilder subPlan, Node node, Iterable<FieldOrExpression> inputs)
    {
        for (FieldOrExpression input : inputs) {
            if (input.isExpression()) {
                Expression rewritten = subPlan.rewrite(input.getExpression());
                subPlan = handleSubqueries(subPlan, rewritten, node);
            }
        }
        return subPlan;
    }

    private PlanBuilder handleSubqueries(PlanBuilder builder, Expression expression, Node node)
    {
        builder = appendSemiJoins(
                builder,
                analysis.getInPredicates(node)
                        .stream()
                        .filter(inPredicate -> nodeContains(expression, inPredicate.getValueList()))
                        .collect(toImmutableSet()));
        builder = appendScalarSubqueryJoins(
                builder,
                analysis.getScalarSubqueries(node)
                        .stream()
                        .filter(subquery -> nodeContains(expression, subquery))
                        .collect(toImmutableSet()));
        return builder;
    }

    private PlanBuilder appendSemiJoins(PlanBuilder subPlan, Set<InPredicate> inPredicates)
    {
        for (InPredicate inPredicate : inPredicates) {
            subPlan = appendSemiJoin(subPlan, inPredicate);
        }
        return subPlan;
    }

    /**
     * Semijoins are planned as follows:
     * 1) SQL constructs that need to be semijoined are extracted during Analysis phase (currently only InPredicates so far)
     * 2) Create a new SemiJoinNode that connects the semijoin lookup field with the planned subquery and have it output a new boolean
     * symbol for the result of the semijoin.
     * 3) Add an entry to the TranslationMap that notes to map the InPredicate into semijoin output symbol
     * <p/>
     * Currently, we only support semijoins deriving from InPredicates, but we will probably need
     * to add support for more SQL constructs in the future.
     */
    private PlanBuilder appendSemiJoin(PlanBuilder subPlan, InPredicate inPredicate)
    {
        TranslationMap translations = copyTranslations(subPlan);

        subPlan = appendProjections(subPlan, ImmutableList.of(inPredicate.getValue()));
        Symbol sourceJoinSymbol = subPlan.translate(inPredicate.getValue());

        checkState(inPredicate.getValueList() instanceof SubqueryExpression);
        SubqueryExpression subqueryExpression = (SubqueryExpression) inPredicate.getValueList();
        RelationPlanner relationPlanner = new RelationPlanner(analysis, symbolAllocator, idAllocator, metadata, session);
        RelationPlan valueListRelation = relationPlanner.process(subqueryExpression.getQuery(), null);
        Symbol filteringSourceJoinSymbol = getOnlyElement(valueListRelation.getRoot().getOutputSymbols());

        Symbol semiJoinOutputSymbol = symbolAllocator.newSymbol("semijoinresult", BOOLEAN);

        translations.put(inPredicate, semiJoinOutputSymbol);

        return new PlanBuilder(translations,
                new SemiJoinNode(idAllocator.getNextId(),
                        subPlan.getRoot(),
                        valueListRelation.getRoot(),
                        sourceJoinSymbol,
                        filteringSourceJoinSymbol,
                        semiJoinOutputSymbol,
                        Optional.empty(),
                        Optional.empty()),
                subPlan.getSampleWeight());
    }

    private TranslationMap copyTranslations(PlanBuilder subPlan)
    {
        TranslationMap translations = new TranslationMap(subPlan.getRelationPlan(), analysis);
        translations.copyMappingsFrom(subPlan.getTranslations());
        return translations;
    }

    private RelationPlan createRelationPlan(SubqueryExpression subqueryExpression)
    {
        return new RelationPlanner(analysis, symbolAllocator, idAllocator, metadata, session)
                .process(subqueryExpression.getQuery(), null);
    }

    private PlanBuilder appendScalarSubqueryJoins(PlanBuilder builder, Set<SubqueryExpression> scalarSubqueries)
    {
        for (SubqueryExpression scalarSubquery : scalarSubqueries) {
            builder = appendScalarSubqueryJoin(builder, scalarSubquery);
        }
        return builder;
    }

    private PlanBuilder appendScalarSubqueryJoin(PlanBuilder builder, SubqueryExpression scalarSubquery)
    {
        EnforceSingleRowNode enforceSingleRowNode = new EnforceSingleRowNode(idAllocator.getNextId(), createRelationPlan(scalarSubquery).getRoot());

        TranslationMap translations = copyTranslations(builder);
        translations.put(scalarSubquery, getOnlyElement(enforceSingleRowNode.getOutputSymbols()));

        // Cross join current (root) relation with subquery
        PlanNode root = builder.getRoot();
        if (root.getOutputSymbols().isEmpty()) {
            // there is nothing to join with - e.g. SELECT (SELECT 1)
            return new PlanBuilder(translations, enforceSingleRowNode, builder.getSampleWeight());
        }
        else {
            return new PlanBuilder(translations,
                    new JoinNode(idAllocator.getNextId(),
                            JoinNode.Type.FULL,
                            root,
                            enforceSingleRowNode,
                            ImmutableList.of(),
                            Optional.empty(),
                            Optional.empty()),
                    builder.getSampleWeight());
        }
    }

    private PlanBuilder distinct(PlanBuilder subPlan, QuerySpecification node, List<FieldOrExpression> outputs, List<FieldOrExpression> orderBy)
    {
        if (node.getSelect().isDistinct()) {
            checkState(outputs.containsAll(orderBy), "Expected ORDER BY terms to be in SELECT. Broken analysis");

            AggregationNode aggregation = new AggregationNode(idAllocator.getNextId(),
                    subPlan.getRoot(),
                    subPlan.getRoot().getOutputSymbols(),
                    ImmutableMap.<Symbol, FunctionCall>of(),
                    ImmutableMap.<Symbol, Signature>of(),
                    ImmutableMap.<Symbol, Symbol>of(),
                    AggregationNode.Step.SINGLE,
                    Optional.empty(),
                    1.0,
                    Optional.empty());

            return new PlanBuilder(subPlan.getTranslations(), aggregation, subPlan.getSampleWeight());
        }

        return subPlan;
    }

    private PlanBuilder sort(PlanBuilder subPlan, Query node)
    {
        return sort(subPlan, node.getOrderBy(), node.getLimit(), analysis.getOrderByExpressions(node));
    }

    private PlanBuilder sort(PlanBuilder subPlan, QuerySpecification node)
    {
        return sort(subPlan, node.getOrderBy(), node.getLimit(), analysis.getOrderByExpressions(node));
    }

    private PlanBuilder sort(PlanBuilder subPlan, List<SortItem> orderBy, Optional<String> limit, List<FieldOrExpression> orderByExpressions)
    {
        if (orderBy.isEmpty()) {
            return subPlan;
        }

        Iterator<SortItem> sortItems = orderBy.iterator();

        ImmutableList.Builder<Symbol> orderBySymbols = ImmutableList.builder();
        Map<Symbol, SortOrder> orderings = new HashMap<>();
        for (FieldOrExpression fieldOrExpression : orderByExpressions) {
            Symbol symbol = subPlan.translate(fieldOrExpression);

            SortItem sortItem = sortItems.next();
            if (!orderings.containsKey(symbol)) {
                orderBySymbols.add(symbol);
                orderings.put(symbol, toSortOrder(sortItem));
            }
        }

        PlanNode planNode;
        if (limit.isPresent() && !limit.get().equalsIgnoreCase("all")) {
            planNode = new TopNNode(idAllocator.getNextId(), subPlan.getRoot(), Long.parseLong(limit.get()), orderBySymbols.build(), orderings, false);
        }
        else {
            planNode = new SortNode(idAllocator.getNextId(), subPlan.getRoot(), orderBySymbols.build(), orderings);
        }

        return new PlanBuilder(subPlan.getTranslations(), planNode, subPlan.getSampleWeight());
    }

    private PlanBuilder limit(PlanBuilder subPlan, Query node)
    {
        return limit(subPlan, node.getOrderBy(), node.getLimit());
    }

    private PlanBuilder limit(PlanBuilder subPlan, QuerySpecification node)
    {
        return limit(subPlan, node.getOrderBy(), node.getLimit());
    }

    private PlanBuilder limit(PlanBuilder subPlan, List<SortItem> orderBy, Optional<String> limit)
    {
        if (orderBy.isEmpty() && limit.isPresent()) {
            if (limit.get().equalsIgnoreCase("all")) {
                return subPlan;
            }
            else {
                long limitValue = Long.parseLong(limit.get());
                return new PlanBuilder(subPlan.getTranslations(), new LimitNode(idAllocator.getNextId(), subPlan.getRoot(), limitValue), subPlan.getSampleWeight());
            }
        }

        return subPlan;
    }

    private SortOrder toSortOrder(SortItem sortItem)
    {
        if (sortItem.getOrdering() == Ordering.ASCENDING) {
            if (sortItem.getNullOrdering() == NullOrdering.FIRST) {
                return SortOrder.ASC_NULLS_FIRST;
            }
            else {
                return SortOrder.ASC_NULLS_LAST;
            }
        }
        else {
            if (sortItem.getNullOrdering() == NullOrdering.FIRST) {
                return SortOrder.DESC_NULLS_FIRST;
            }
            else {
                return SortOrder.DESC_NULLS_LAST;
            }
        }
    }
}
