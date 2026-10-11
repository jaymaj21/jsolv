package org.jsolv.checker;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.Diagnostic;

/** JSOLV: generic checking of application-defined value-flow labels. */
@SupportedAnnotationTypes("*")
@SupportedOptions(JsolvProcessor.REPORT_OPTION)
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public final class JsolvProcessor extends AbstractProcessor {
    static final String REPORT_OPTION = "jsolv.report";
    private static final String MARKER = "org.jsolv.StaticTag";
    private static final String PRESERVES = "org.jsolv.PreservesTags";
    private Trees trees;
    private final List<ReportEntry> reportEntries = new ArrayList<>();
    private boolean reportWritten;

    @Override public synchronized void init(ProcessingEnvironment environment) {
        super.init(environment);
        trees = Trees.instance(environment);
    }

    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) { writeReportIfRequested(); return false; }
        for (Element root : round.getRootElements()) {
            TreePath path = trees.getPath(root);
            if (path != null) new MethodBodyScanner().scan(path, null);
        }
        return false; // Do not claim other processors' annotations.
    }

    private final class MethodBodyScanner extends TreePathScanner<Void, Void> {
        @Override public Void visitMethod(MethodTree node, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement method) {
                validatePreservation(method, node, getCurrentPath());
                if (node.getBody() != null) {
                    FlowState state = new FlowState();
                    for (VariableElement parameter : method.getParameters())
                        state.values.put(parameter, labels(parameter));
                    new FlowScanner().scan(new TreePath(getCurrentPath(), node.getBody()), state);
                }
            }
            return null;
        }
    }

    private final class FlowScanner extends TreePathScanner<Void, FlowState> {
        private Set<String> evaluate(ExpressionTree expression, FlowState state) {
            if (expression == null) return Set.of();
            TreePath path = TreePath.getPath(getCurrentPath(), expression);
            if (path == null) return Set.of();
            if (expression instanceof ParenthesizedTree parens) return evaluate(parens.getExpression(), state);
            if (expression instanceof TypeCastTree cast) return evaluate(cast.getExpression(), state);
            if (expression instanceof BinaryTree binary) {
                evaluate(binary.getLeftOperand(), state);
                if (binary.getKind() == Tree.Kind.CONDITIONAL_AND || binary.getKind() == Tree.Kind.CONDITIONAL_OR) {
                    FlowState skipped = state.copy(), executed = state.copy();
                    evaluate(binary.getRightOperand(), executed);
                    state.join(skipped, executed);
                } else evaluate(binary.getRightOperand(), state);
                return Set.of();
            }
            if (expression instanceof CompoundAssignmentTree compound) {
                evaluate(compound.getExpression(), state);
                assign(compound.getVariable(), Set.of(), state);
                return Set.of();
            }
            if (expression instanceof IdentifierTree) {
                return state.values.getOrDefault(trees.getElement(path), Set.of());
            }
            if (expression instanceof AssignmentTree assignment) {
                Set<String> value = evaluate(assignment.getExpression(), state);
                assign(assignment.getVariable(), value, state);
                return value;
            }
            if (expression instanceof ConditionalExpressionTree conditional) {
                evaluate(conditional.getCondition(), state);
                FlowState yes = state.copy(), no = state.copy();
                Set<String> value = new TreeSet<>(evaluate(conditional.getTrueExpression(), yes));
                value.retainAll(evaluate(conditional.getFalseExpression(), no));
                state.join(yes, no);
                return value;
            }
            if (expression instanceof MethodInvocationTree invocation) {
                // Evaluate receiver and every argument, including calls inside initializers.
                if (invocation.getMethodSelect() instanceof MemberSelectTree select)
                    evaluate(select.getExpression(), state);
                List<Set<String>> arguments = new ArrayList<>();
                for (ExpressionTree argument : invocation.getArguments()) arguments.add(evaluate(argument, state));
                Element element = trees.getElement(path);
                if (!(element instanceof ExecutableElement method)) return Set.of();
                checkArguments(path, invocation.getArguments(), arguments, method);
                Set<String> result = new TreeSet<>(labels(method.getReturnType()));
                result.addAll(labelsFrom(method.getAnnotationMirrors()));
                int preserved = preservedArgument(method);
                if (preserved > 0 && preserved <= arguments.size()) result.addAll(arguments.get(preserved - 1));
                return result;
            }
            if (expression instanceof NewClassTree constructor) {
                evaluate(constructor.getEnclosingExpression(), state);
                List<Set<String>> arguments = new ArrayList<>();
                for (ExpressionTree argument : constructor.getArguments()) arguments.add(evaluate(argument, state));
                Element element = trees.getElement(path);
                if (element instanceof ExecutableElement method)
                    checkArguments(path, constructor.getArguments(), arguments, method);
                return Set.of();
            }
            // Walk nested expressions for diagnostics; unsupported expressions establish no labels.
            new FlowScanner().scan(path, state);
            return Set.of();
        }

        @Override public Void visitVariable(VariableTree node, FlowState state) {
            Set<String> value = evaluate(node.getInitializer(), state);
            Element variable = trees.getElement(getCurrentPath());
            if (node.getInitializer() != null) require(labels(variable), value, node.getInitializer(), getCurrentPath());
            if (variable != null) state.values.put(variable, Set.copyOf(value));
            return null;
        }
        @Override public Void visitExpressionStatement(ExpressionStatementTree node, FlowState state) {
            evaluate(node.getExpression(), state); return null;
        }
        @Override public Void visitMethodInvocation(MethodInvocationTree node, FlowState state) {
            evaluate(node, state); return null;
        }
        @Override public Void visitNewClass(NewClassTree node, FlowState state) {
            evaluate(node, state); return null;
        }
        @Override public Void visitAssignment(AssignmentTree node, FlowState state) {
            evaluate(node, state); return null;
        }
        @Override public Void visitIf(IfTree node, FlowState state) {
            evaluate(node.getCondition(), state);
            FlowState yes = state.copy(), no = state.copy();
            scan(node.getThenStatement(), yes);
            scan(node.getElseStatement(), no);
            state.join(yes, no);
            return null;
        }
        @Override public Void visitReturn(ReturnTree node, FlowState state) {
            evaluate(node.getExpression(), state); return null;
        }
        @Override public Void visitLambdaExpression(LambdaExpressionTree node, FlowState state) {
            // Lambda parameters have their own contracts; captured locals are deliberately unknown.
            FlowState lambda = new FlowState();
            for (VariableTree parameter : node.getParameters()) {
                Element element = trees.getElement(new TreePath(getCurrentPath(), parameter));
                if (element != null) lambda.values.put(element, labels(element));
            }
            scan(node.getBody(), lambda); return null;
        }
        @Override public Void visitClass(ClassTree node, FlowState state) {
            new MethodBodyScanner().scan(getCurrentPath(), null); return null;
        }
        // Loops/try/switch do not establish post-statement facts. Forget potentially
        // assigned values before scanning, so a previous iteration cannot supply a false proof.
        private Void uncertainControl(Tree node, FlowState state) {
            Set<Element> written = new HashSet<>();
            new TreePathScanner<Void, Void>() {
                @Override public Void visitAssignment(AssignmentTree assignment, Void unused) {
                    written.add(trees.getElement(new TreePath(getCurrentPath(), assignment.getVariable())));
                    return super.visitAssignment(assignment, unused);
                }
            }.scan(getCurrentPath(), null);
            written.forEach(state.values::remove);
            FlowState isolated = state.copy();
            // Dispatch to the superclass to avoid re-entering this override.
            switch (node.getKind()) {
                case WHILE_LOOP -> super.visitWhileLoop((WhileLoopTree) node, isolated);
                case DO_WHILE_LOOP -> super.visitDoWhileLoop((DoWhileLoopTree) node, isolated);
                case FOR_LOOP -> {
                    ForLoopTree loop = (ForLoopTree) node;
                    scan(loop.getInitializer(), isolated);
                    written.forEach(isolated.values::remove);
                    evaluate(loop.getCondition(), isolated);
                    scan(loop.getStatement(), isolated);
                    scan(loop.getUpdate(), isolated);
                }
                case ENHANCED_FOR_LOOP -> super.visitEnhancedForLoop((EnhancedForLoopTree) node, isolated);
                case TRY -> {
                    TryTree attempt = (TryTree) node;
                    scan(attempt.getResources(), isolated);
                    scan(attempt.getBlock(), isolated.copy());
                    for (CatchTree caught : attempt.getCatches()) scan(caught, isolated.copy());
                    scan(attempt.getFinallyBlock(), isolated.copy());
                }
                case SWITCH -> {
                    SwitchTree selection = (SwitchTree) node;
                    evaluate(selection.getExpression(), isolated);
                    for (CaseTree branch : selection.getCases()) scan(branch, isolated.copy());
                }
                default -> throw new IllegalStateException();
            }
            written.forEach(state.values::remove);
            return null;
        }
        @Override public Void visitWhileLoop(WhileLoopTree n, FlowState s) { return uncertainControl(n,s); }
        @Override public Void visitDoWhileLoop(DoWhileLoopTree n, FlowState s) { return uncertainControl(n,s); }
        @Override public Void visitForLoop(ForLoopTree n, FlowState s) { return uncertainControl(n,s); }
        @Override public Void visitEnhancedForLoop(EnhancedForLoopTree n, FlowState s) { return uncertainControl(n,s); }
        @Override public Void visitTry(TryTree n, FlowState s) { return uncertainControl(n,s); }
        @Override public Void visitSwitch(SwitchTree n, FlowState s) { return uncertainControl(n,s); }
        @Override public Void visitBinary(BinaryTree node, FlowState state) {
            evaluate(node, state); return null;
        }
        @Override public Void visitCompoundAssignment(CompoundAssignmentTree node, FlowState state) {
            evaluate(node, state); return null;
        }
        @Override public Void visitSwitchExpression(SwitchExpressionTree node, FlowState state) {
            // Each alternative starts with no facts, and can supply no post-expression facts.
            evaluate(node.getExpression(), state);
            for (CaseTree branch : node.getCases()) scan(branch, new FlowState());
            state.values.clear();
            return null;
        }

        private void assign(ExpressionTree target, Set<String> value, FlowState state) {
            TreePath path = TreePath.getPath(getCurrentPath(), target);
            Element variable = path == null ? null : trees.getElement(path);
            if (variable == null) return;
            require(labels(variable), value, target, path);
            if (variable.getKind() == ElementKind.LOCAL_VARIABLE || variable.getKind() == ElementKind.PARAMETER)
                state.values.put(variable, Set.copyOf(value));
        }

        private void checkArguments(TreePath path, List<? extends ExpressionTree> expressions,
                List<Set<String>> arguments, ExecutableElement method) {
            List<? extends VariableElement> parameters = method.getParameters();
            for (int i = 0; i < arguments.size() && !parameters.isEmpty(); i++) {
                int index = Math.min(i, parameters.size() - 1);
                if (i >= parameters.size() && !method.isVarArgs()) break;
                VariableElement parameter = parameters.get(index);
                Set<String> required = new TreeSet<>(labels(parameter));
                if (method.isVarArgs() && index == parameters.size() - 1
                        && parameter.asType() instanceof ArrayType array) required.addAll(labels(array.getComponentType()));
                if (required.isEmpty()) continue;
                Set<String> known = arguments.get(i);
                reportEntries.add(reportEntry(path, expressions.get(i), method, i + 1, required, known));
                require(required, known, expressions.get(i), path);
            }
        }
    }

    private Set<String> labels(Element element) {
        if (element == null) return Set.of();
        Set<String> result = new TreeSet<>(labels(element.asType()));
        result.addAll(labelsFrom(element.getAnnotationMirrors()));
        return result;
    }
    private Set<String> labels(TypeMirror type) {
        return type == null ? Set.of() : labelsFrom(type.getAnnotationMirrors());
    }
    private Set<String> labelsFrom(List<? extends AnnotationMirror> mirrors) {
        Set<String> result = new TreeSet<>();
        for (AnnotationMirror mirror : mirrors) {
            Element annotation = mirror.getAnnotationType().asElement();
            if (annotation.getAnnotationMirrors().stream().anyMatch(m -> m.getAnnotationType().toString().equals(MARKER)))
                result.add(((TypeElement) annotation).getQualifiedName().toString());
        }
        return result;
    }
    private int preservedArgument(ExecutableElement method) {
        for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(PRESERVES)) {
                for (var entry : processingEnv.getElementUtils().getElementValuesWithDefaults(mirror).entrySet())
                    if (entry.getKey().getSimpleName().contentEquals("value")) return (Integer) entry.getValue().getValue();
            }
        }
        return 0;
    }
    private void validatePreservation(ExecutableElement method, Tree tree, TreePath path) {
        boolean annotated = method.getAnnotationMirrors().stream().anyMatch(m -> m.getAnnotationType().toString().equals(PRESERVES));
        int index = preservedArgument(method);
        if (annotated && (index < 1 || index > method.getParameters().size()
                || method.getReturnType().getKind() == TypeKind.VOID
                || method.isVarArgs() && index == method.getParameters().size()))
            error("@PreservesTags requires a non-void return and a valid one-based, non-varargs parameter index", tree, path);
    }
    private void require(Set<String> required, Set<String> known, Tree tree, TreePath path) {
        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(known);
        if (!missing.isEmpty()) error("missing " + missing.stream().map(s -> "@" + s.substring(s.lastIndexOf('.') + 1))
                .collect(java.util.stream.Collectors.joining(", ")) + ": value lacks required static tags " + missing, tree, path);
    }
    private void error(String message, Tree tree, TreePath path) {
        trees.printMessage(Diagnostic.Kind.ERROR, message, tree, path.getCompilationUnit());
    }
    private static final class FlowState {
        private final Map<Element, Set<String>> values = new HashMap<>();
        private FlowState copy() { FlowState copy = new FlowState(); copy.values.putAll(values); return copy; }
        private void join(FlowState yes, FlowState no) {
            values.clear();
            yes.values.forEach((variable, labels) -> {
                Set<String> common = new TreeSet<>(labels);
                common.retainAll(no.values.getOrDefault(variable, Set.of()));
                values.put(variable, Set.copyOf(common));
            });
        }
    }

    private ReportEntry reportEntry(
            TreePath invocationPath,
            ExpressionTree argument,
            ExecutableElement method,
            int argumentIndex,
            Set<String> required, Set<String> known) {
        long line = -1;
        long column = -1;
        String source = "<unknown>";

        if (invocationPath != null) {
            CompilationUnitTree unit = invocationPath.getCompilationUnit();
            source = unit.getSourceFile() == null ? "<unknown>" : unit.getSourceFile().toUri().toString();
            long start = trees.getSourcePositions().getStartPosition(unit, argument);
            if (start >= 0 && unit.getLineMap() != null) {
                line = unit.getLineMap().getLineNumber(start);
                column = unit.getLineMap().getColumnNumber(start);
            }
        }

        return new ReportEntry(
                source,
                line,
                column,
                method.getSimpleName().toString(),
                argumentIndex,
                known.containsAll(required),
                Set.copyOf(required), Set.copyOf(known));
    }

    private void writeReportIfRequested() {
        if (reportWritten) {
            return;
        }
        reportWritten = true;

        String reportPath = processingEnv.getOptions().get(REPORT_OPTION);
        if (reportPath == null || reportPath.isBlank()) {
            return;
        }

        Path path = Path.of(reportPath);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, reportJson(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "failed to write static tags report to " + reportPath + ": " + error.getMessage());
        }
    }

    private String reportJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schema\": 2,\n");
        json.append("  \"processor\": \"").append(JsolvProcessor.class.getName()).append("\",\n");
        json.append("  \"checks\": [\n");
        for (int i = 0; i < reportEntries.size(); i++) {
            ReportEntry entry = reportEntries.get(i);
            json.append("    {\n");
            json.append("      \"source\": \"").append(jsonEscape(entry.source)).append("\",\n");
            json.append("      \"line\": ").append(entry.line).append(",\n");
            json.append("      \"column\": ").append(entry.column).append(",\n");
            json.append("      \"method\": \"").append(jsonEscape(entry.method)).append("\",\n");
            json.append("      \"argumentIndex\": ").append(entry.argumentIndex).append(",\n");
            json.append("      \"required\": ").append(labelJson(entry.required)).append(",\n");
            json.append("      \"known\": ").append(labelJson(entry.known)).append(",\n");
            Set<String> missing = new TreeSet<>(entry.required);
            missing.removeAll(entry.known);
            json.append("      \"missing\": ").append(labelJson(missing)).append(",\n");
            json.append("      \"accepted\": ").append(entry.accepted).append("\n");
            json.append("    }");
            if (i + 1 < reportEntries.size()) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private String labelJson(Set<String> labels) {
        return labels.stream().sorted().map(s -> "\"" + jsonEscape(s) + "\"")
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record ReportEntry(
            String source,
            long line,
            long column,
            String method,
            int argumentIndex,
            boolean accepted, Set<String> required, Set<String> known) {
    }
}
