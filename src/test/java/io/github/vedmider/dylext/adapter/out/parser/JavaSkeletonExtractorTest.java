package io.github.vedmider.dylext.adapter.out.parser;

import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.model.SymbolKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSkeletonExtractorTest {

    private final JavaSkeletonExtractor extractor = new JavaSkeletonExtractor();

    private static final String SOURCE = """
            package com.acme.billing;

            import java.util.List;
            import java.math.BigDecimal;

            /** Computes invoice totals for a customer. */
            public class InvoiceCalculator {

                private final BigDecimal taxRate;

                public InvoiceCalculator(BigDecimal taxRate) {
                    this.taxRate = taxRate;
                }

                /** Returns the grand total including tax. */
                public BigDecimal total(List<BigDecimal> lineItems) {
                    BigDecimal sum = lineItems.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    return sum.add(sum.multiply(taxRate));
                }
            }
            """;

    private List<Symbol> extract() {
        return extractor.extract("demo", "src/main/java/com/acme/billing/InvoiceCalculator.java", SOURCE);
    }

    @Test
    void overloadedMethodsGetDistinctSymbolPathsAndAreAllKept() {
        String src = """
                package d;
                public class Ovl {
                    public String greet(String who){ return who; }
                    public String greet(int who){ return "" + who; }
                    public String greet(String a, int b){ return a + b; }
                }
                """;
        List<Symbol> syms = extractor.extract("demo", "d/Ovl.java", src);
        List<String> greetPaths = syms.stream()
                .filter(s -> s.kind() == SymbolKind.METHOD && s.name().equals("greet"))
                .map(Symbol::symbolPath).toList();
        assertThat(greetPaths).containsExactlyInAnyOrder(
                "d.Ovl#greet(String)", "d.Ovl#greet(int)", "d.Ovl#greet(String,int)");
        assertThat(syms.stream().map(Symbol::id).distinct().count()).isEqualTo(syms.size());
    }

    @Test
    void inferredParamTypesTagCallTargetsWithAllLiteralOrNewArgs() {
        String src = """
                package d;
                public class Caller {
                    void run() {
                        greet("hi");
                        greet(42);
                        pair("a", 2);
                        make(new Thing());
                        mixed("a", other());
                        none();
                    }
                }
                """;
        List<String> calls = extractor.extract("demo", "d/Caller.java", src).stream()
                .filter(s -> s.name().equals("run")).findFirst().orElseThrow().callTargets();

        assertThat(calls).contains("this.greet/1#String", "this.greet/1#int", "this.pair/2#String,int",
                "this.make/1#Thing", "this.none/0", "Thing/0");
        // one un-inferable arg -> no type hint at all, falls back to bare name/arity
        assertThat(calls).contains("this.mixed/2").doesNotContain("this.mixed/2#String");
    }

    @Test
    void emitsModuleWithPackageImportsAndTypeHeaders() {
        Symbol module = extract().get(0);
        assertThat(module.kind()).isEqualTo(SymbolKind.MODULE);
        assertThat(module.signature())
                .contains("package com.acme.billing;")
                .contains("import java.util.List;")
                .contains("import java.math.BigDecimal;")
                .contains("public class InvoiceCalculator");
        assertThat(module.callTargets()).contains("java.util.List", "java.math.BigDecimal");
    }

    @Test
    void emitsClassMethodConstructorAndFieldSymbols() {
        List<Symbol> syms = extract();
        assertThat(syms).extracting(Symbol::symbolPath)
                .contains(
                        "com.acme.billing.InvoiceCalculator",
                        "com.acme.billing.InvoiceCalculator#InvoiceCalculator(BigDecimal)",
                        "com.acme.billing.InvoiceCalculator#total(List<BigDecimal>)",
                        "com.acme.billing.InvoiceCalculator#taxRate");
    }

    @Test
    void methodSignatureCarriesReturnTypeAndParamsButNotBody() {
        Symbol total = extract().stream()
                .filter(s -> s.name().equals("total")).findFirst().orElseThrow();
        assertThat(total.kind()).isEqualTo(SymbolKind.METHOD);
        assertThat(total.signature()).isEqualTo("public BigDecimal total(List<BigDecimal> lineItems)");
        assertThat(total.signature()).doesNotContain("return", "reduce");
    }

    @Test
    void methodCapturesDocFirstLineAndCallTargets() {
        Symbol total = extract().stream()
                .filter(s -> s.name().equals("total")).findFirst().orElseThrow();
        assertThat(total.docFirstLine()).isEqualTo("Returns the grand total including tax.");
        assertThat(total.callTargets()).contains("stream/0", "reduce/2", "add/1", "multiply/1");
    }

    @Test
    void fieldSignatureCarriesModifiersTypeAndName() {
        Symbol field = extract().stream()
                .filter(s -> s.name().equals("taxRate") && s.kind() == SymbolKind.FIELD)
                .findFirst().orElseThrow();
        assertThat(field.signature()).isEqualTo("private final BigDecimal taxRate");
    }

    @Test
    void skeletonIsAFractionOfSourceSize() {
        int sourceWords = SOURCE.split("\\s+").length;
        int skeletonWords = extract().stream()
                .mapToInt(s -> s.signature().split("\\s+").length).sum();
        assertThat(skeletonWords).isLessThan(sourceWords);
    }

    @Test
    void parsesJava21TextBlocksIntoRealSymbols() {
        String src = """
                package p;
                public class WithTextBlock {
                    public String sql() {
                        return \"""
                            SELECT 1
                            \""";
                    }
                }
                """;
        List<Symbol> syms = extractor.extract("demo", "p/WithTextBlock.java", src);
        assertThat(syms).extracting(Symbol::name).contains("WithTextBlock", "sql");
        assertThat(syms).noneMatch(s -> s.signature().contains("unparsed"));
    }

    @Test
    void concurrentExtractionIsThreadSafe() throws Exception {
        int threads = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    List<Symbol> syms = extract();
                    boolean total = syms.stream().anyMatch(s -> s.name().equals("total"));
                    boolean ctor = syms.stream().anyMatch(s -> s.name().equals("InvoiceCalculator"));
                    return total && ctor && syms.size() >= 4;
                });
            }
            for (var f : pool.invokeAll(tasks)) {
                assertThat(f.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void malformedSourceDegradesToSingleModuleSymbol() {
        List<Symbol> syms = extractor.extract("demo", "Broken.java", "public class { this is not java");
        assertThat(syms).hasSize(1);
        assertThat(syms.get(0).kind()).isEqualTo(SymbolKind.MODULE);
    }

    @Test
    void lineRangesArePopulated() {
        Symbol total = extract().stream()
                .filter(s -> s.name().equals("total")).findFirst().orElseThrow();
        assertThat(total.startLine()).isGreaterThan(0);
        assertThat(total.endLine()).isGreaterThanOrEqualTo(total.startLine());
    }
}
