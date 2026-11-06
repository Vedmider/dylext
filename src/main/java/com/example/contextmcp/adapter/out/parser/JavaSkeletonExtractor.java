package com.example.contextmcp.adapter.out.parser;

import com.example.contextmcp.domain.model.Hashing;
import com.example.contextmcp.domain.model.Symbol;
import com.example.contextmcp.domain.model.SymbolKind;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extractive Java skeletons via JavaParser. Emits names + signatures + imports + call targets so
 * functional intent is inferable without the bodies. Malformed source degrades to a MODULE symbol.
 */
@Component
public class JavaSkeletonExtractor implements com.example.contextmcp.domain.port.out.SkeletonExtractor {

    // JavaParser is not thread-safe; give each thread its own so concurrent index/sync can't corrupt it.
    private final ThreadLocal<JavaParser> parser = ThreadLocal.withInitial(() -> new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)));

    @Override
    public String language() {
        return "java";
    }

    @Override
    public List<Symbol> extract(String repo, String filePath, String source) {
        String hash = Hashing.sha256(source);
        int lineCount = (int) source.lines().count();
        CompilationUnit cu;
        try {
            ParseResult<CompilationUnit> result = parser.get().parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return List.of(moduleSymbol(repo, filePath, hash, 1, Math.max(1, lineCount),
                        "// unparsed (" + lineCount + " lines)", List.of()));
            }
            cu = result.getResult().get();
        } catch (RuntimeException parseFailure) {
            return List.of(moduleSymbol(repo, filePath, hash, 1, Math.max(1, lineCount),
                    "// unparsed (" + lineCount + " lines)", List.of()));
        }

        List<Symbol> out = new ArrayList<>();
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        List<String> imports = cu.getImports().stream().map(i -> i.getNameAsString()).toList();

        List<String> typeLines = new ArrayList<>();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            typeLines.add(typeHeader(type));
            collectType(repo, filePath, pkg, type, out);
        }

        String moduleSkeleton = moduleSkeletonText(pkg, imports, typeLines);
        out.add(0, moduleSymbol(repo, filePath, hash, 1, Math.max(1, lineCount), moduleSkeleton, imports));
        return out;
    }

    private void collectType(String repo, String file, String pkg, TypeDeclaration<?> type,
                             List<Symbol> out) {
        String typeName = type.getNameAsString();
        String typePath = pkg.isEmpty() ? typeName : pkg + "." + typeName;
        out.add(symbol(repo, file, typePath, type, kindOf(type), typeName, typeHeader(type), null));

        type.getMembers().forEach(member -> {
            if (member instanceof MethodDeclaration m) {
                out.add(symbol(repo, file, typePath + "#" + m.getNameAsString(), m,
                        SymbolKind.METHOD, m.getNameAsString(), signatureOf(m), m));
            } else if (member instanceof ConstructorDeclaration c) {
                out.add(symbol(repo, file, typePath + "#" + c.getNameAsString(), c,
                        SymbolKind.CONSTRUCTOR, c.getNameAsString(), signatureOf(c), c));
            } else if (member instanceof FieldDeclaration f) {
                for (VariableDeclarator v : f.getVariables()) {
                    out.add(symbol(repo, file, typePath + "#" + v.getNameAsString(), f,
                            SymbolKind.FIELD, v.getNameAsString(), fieldSignature(f, v), f));
                }
            } else if (member instanceof TypeDeclaration<?> nested) {
                collectType(repo, file, typePath, nested, out);
            }
        });
    }

    private Symbol symbol(String repo, String file, String symbolPath,
                          com.github.javaparser.ast.Node node, SymbolKind kind, String name,
                          String signature, com.github.javaparser.ast.Node bodyNode) {
        int start = node.getBegin().map(p -> p.line).orElse(1);
        int end = node.getEnd().map(p -> p.line).orElse(start);
        String doc = firstDocLine(node);
        List<String> calls = bodyNode == null ? List.of() : callTargets(bodyNode);
        String hash = Hashing.sha256(signature + "|" + doc + "|" + String.join(",", calls));
        return new Symbol(Symbol.idOf(repo, file, symbolPath), repo, file, "java", kind, name,
                symbolPath, signature, doc, start, end, calls, hash);
    }

    private Symbol moduleSymbol(String repo, String file, String hash, int start, int end,
                                String skeleton, List<String> imports) {
        return new Symbol(Symbol.idOf(repo, file, "<module>"), repo, file, "java", SymbolKind.MODULE,
                moduleName(file), "<module>", skeleton, "", start, end, imports, hash);
    }

    private static String moduleName(String file) {
        int slash = file.lastIndexOf('/');
        return slash >= 0 ? file.substring(slash + 1) : file;
    }

    private static String moduleSkeletonText(String pkg, List<String> imports, List<String> typeLines) {
        StringBuilder sb = new StringBuilder();
        if (!pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n");
        }
        imports.forEach(i -> sb.append("import ").append(i).append(";\n"));
        if (!imports.isEmpty()) {
            sb.append('\n');
        }
        typeLines.forEach(t -> sb.append(t).append('\n'));
        return sb.toString().stripTrailing();
    }

    private static String typeHeader(TypeDeclaration<?> type) {
        String kw = switch (kindOf(type)) {
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            default -> "class";
        };
        return type.getModifiers().stream().map(m -> m.getKeyword().asString() + " ").reduce("", String::concat)
                + kw + " " + type.getNameAsString();
    }

    private static SymbolKind kindOf(TypeDeclaration<?> type) {
        String n = type.getClass().getSimpleName();
        if (n.contains("Enum")) {
            return SymbolKind.ENUM;
        }
        if (n.contains("Record")) {
            return SymbolKind.RECORD;
        }
        if (n.contains("Annotation")) {
            return SymbolKind.INTERFACE;
        }
        if (type.isClassOrInterfaceDeclaration()
                && type.asClassOrInterfaceDeclaration().isInterface()) {
            return SymbolKind.INTERFACE;
        }
        return SymbolKind.CLASS;
    }

    private static String signatureOf(CallableDeclaration<?> c) {
        String mods = c.getModifiers().stream().map(m -> m.getKeyword().asString() + " ")
                .reduce("", String::concat);
        String ret = c instanceof MethodDeclaration m ? m.getType().asString() + " " : "";
        String params = c.getParameters().stream()
                .map(p -> p.getType().asString() + " " + p.getNameAsString())
                .reduce((a, b) -> a + ", " + b).orElse("");
        return (mods + ret + c.getNameAsString() + "(" + params + ")").strip();
    }

    private static String fieldSignature(FieldDeclaration f, VariableDeclarator v) {
        String mods = f.getModifiers().stream().map(m -> m.getKeyword().asString() + " ")
                .reduce("", String::concat);
        return (mods + v.getType().asString() + " " + v.getNameAsString()).strip();
    }

    private static String firstDocLine(com.github.javaparser.ast.Node node) {
        return node.getComment()
                .map(Comment::getContent)
                .map(JavaSkeletonExtractor::firstMeaningfulLine)
                .orElse("");
    }

    private static String firstMeaningfulLine(String content) {
        for (String raw : content.split("\n")) {
            String line = raw.strip().replaceFirst("^\\*+", "").strip();
            if (!line.isEmpty() && !line.equals("/")) {
                return line;
            }
        }
        return "";
    }

    private static List<String> callTargets(com.github.javaparser.ast.Node node) {
        Set<String> targets = new LinkedHashSet<>();
        node.findAll(MethodCallExpr.class).forEach(call -> targets.add(call.getNameAsString()));
        return new ArrayList<>(targets);
    }
}
