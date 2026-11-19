package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.ImpactUseCase;
import io.github.vedmider.dylext.domain.port.in.ImpactUseCase.Impact;
import io.github.vedmider.dylext.domain.port.in.IndexUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class ImpactServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IndexUseCase index;

    @Autowired
    private ImpactUseCase impact;

    private static final String SERVICE = """
            package demo;
            public class Greeter {
                public String greet(String who) { return build(who); }
                public String build(String who) { return "hi " + who; }
            }
            """;

    private static final String CALLER = """
            package demo;
            public class Caller {
                public String run() { return new Greeter().greet("x"); }
            }
            """;

    private static final String TEST = """
            package demo;
            public class GreeterTest {
                public void checksGreeting() { new Greeter().greet("y"); }
            }
            """;

    private String indexFixture(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Greeter.java"), SERVICE);
        Files.writeString(dir.resolve("demo/Caller.java"), CALLER);
        Files.writeString(dir.resolve("demo/GreeterTest.java"), TEST);
        String repo = "imp";
        index.index(repo, dir.toString());
        return repo;
    }

    @Test
    void resolvesCallersOfASymbol(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);
        String greetId = "imp:demo/Greeter.java:demo.Greeter#greet(String)";

        Impact i = impact.impactOf(greetId);

        assertThat(i.callers())
                .contains("imp:demo/Caller.java:demo.Caller#run()",
                        "imp:demo/GreeterTest.java:demo.GreeterTest#checksGreeting()");
    }

    @Test
    void separatesTestsFromOrdinaryCallers(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);
        String greetId = "imp:demo/Greeter.java:demo.Greeter#greet(String)";

        Impact i = impact.impactOf(greetId);

        assertThat(i.tests()).containsExactly("imp:demo/GreeterTest.java:demo.GreeterTest#checksGreeting()");
    }

    @Test
    void internalCallIsResolvedToo(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);
        String buildId = "imp:demo/Greeter.java:demo.Greeter#build(String)";

        Impact i = impact.impactOf(buildId);

        assertThat(i.callers()).contains("imp:demo/Greeter.java:demo.Greeter#greet(String)");
    }

    @Test
    void aggregatesBlastRadiusForAClassSymbol(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);
        String classId = "imp:demo/Greeter.java:demo.Greeter";

        Impact i = impact.impactOf(classId);

        assertThat(i.callers())
                .contains("imp:demo/Caller.java:demo.Caller#run()",
                        "imp:demo/GreeterTest.java:demo.GreeterTest#checksGreeting()");
        assertThat(i.tests())
                .contains("imp:demo/GreeterTest.java:demo.GreeterTest#checksGreeting()");
        assertThat(i.callers())
                .noneMatch(c -> c.startsWith("imp:demo/Greeter.java:demo.Greeter#"));
    }

    @Test
    void overloadsResolveByCallSiteArity(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Greeter.java"), """
                package demo;
                public class Greeter {
                    public String greet(String who) { return who; }
                    public String greet(String who, int times) { return who + times; }
                }
                """);
        Files.writeString(dir.resolve("demo/Caller.java"), """
                package demo;
                public class Caller {
                    public String one() { return new Greeter().greet("x"); }
                    public String two() { return new Greeter().greet("x", 2); }
                }
                """);
        index.index("ovl-arity", dir.toString());

        Impact one = impact.impactOf("ovl-arity:demo/Greeter.java:demo.Greeter#greet(String)");
        Impact two = impact.impactOf("ovl-arity:demo/Greeter.java:demo.Greeter#greet(String,int)");

        assertThat(one.callers()).contains("ovl-arity:demo/Caller.java:demo.Caller#one()")
                .doesNotContain("ovl-arity:demo/Caller.java:demo.Caller#two()");
        assertThat(two.callers()).contains("ovl-arity:demo/Caller.java:demo.Caller#two()")
                .doesNotContain("ovl-arity:demo/Caller.java:demo.Caller#one()");
    }

    @Test
    void sameArityOverloadResolvesByInferredLiteralType(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Greeter.java"), """
                package demo;
                public class Greeter {
                    public String greet(String who) { return who; }
                    public String greet(int who) { return "" + who; }
                }
                """);
        Files.writeString(dir.resolve("demo/Caller.java"), """
                package demo;
                public class Caller {
                    public String s() { return new Greeter().greet("x"); }
                    public String i() { return new Greeter().greet(7); }
                }
                """);
        index.index("ovl-type", dir.toString());

        Impact str = impact.impactOf("ovl-type:demo/Greeter.java:demo.Greeter#greet(String)");
        Impact in = impact.impactOf("ovl-type:demo/Greeter.java:demo.Greeter#greet(int)");

        assertThat(str.callers()).contains("ovl-type:demo/Caller.java:demo.Caller#s()")
                .doesNotContain("ovl-type:demo/Caller.java:demo.Caller#i()");
        assertThat(in.callers()).contains("ovl-type:demo/Caller.java:demo.Caller#i()")
                .doesNotContain("ovl-type:demo/Caller.java:demo.Caller#s()");
        assertThat(str.ambiguousOverload()).isFalse();
        assertThat(in.ambiguousOverload()).isFalse();
    }

    @Test
    void unsplittableSameArityOverloadFansOutAndFlagsAmbiguous(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Greeter.java"), """
                package demo;
                public class Greeter {
                    public String greet(String who) { return who; }
                    public String greet(int who) { return "" + who; }
                }
                """);
        Files.writeString(dir.resolve("demo/Caller.java"), """
                package demo;
                public class Caller {
                    public String via(String who) { return new Greeter().greet(who); }
                }
                """);
        index.index("ovl-amb", dir.toString());

        Impact str = impact.impactOf("ovl-amb:demo/Greeter.java:demo.Greeter#greet(String)");
        Impact in = impact.impactOf("ovl-amb:demo/Greeter.java:demo.Greeter#greet(int)");

        String via = "ovl-amb:demo/Caller.java:demo.Caller#via(String)";
        assertThat(str.callers()).contains(via);
        assertThat(in.callers()).contains(via);
        assertThat(str.ambiguousOverload()).isTrue();
        assertThat(in.ambiguousOverload()).isTrue();
    }

    @Test
    void aFieldNamedLikeACalledMethodIsNeverACallTarget(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Port.java"),
                "package demo;\npublic interface Port { String retrieve(String q); }\n");
        Files.writeString(dir.resolve("demo/Harness.java"), """
                package demo;
                public class Harness {
                    private final Port retrieve;
                    public Harness(Port retrieve) { this.retrieve = retrieve; }
                    public int size() { return 1; }
                }
                """);
        Files.writeString(dir.resolve("demo/Service.java"), """
                package demo;
                public class Service {
                    private final Port retrieve;
                    public Service(Port retrieve) { this.retrieve = retrieve; }
                    public String answer(String q) { return retrieve.retrieve(q); }
                }
                """);
        index.index("fld", dir.toString());

        Impact harness = impact.impactOf("fld:demo/Harness.java:demo.Harness");

        assertThat(harness.callers()).noneMatch(c -> c.contains("demo.Service#answer"));
        assertThat(impact.impactOf("fld:demo/Port.java:demo.Port#retrieve(String)").callers())
                .contains("fld:demo/Service.java:demo.Service#answer(String)");
    }

    @Test
    void constructorCallsAreCallersOfTheClass(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Point.java"),
                "package demo;\npublic record Point(int x, int y) { }\n");
        Files.writeString(dir.resolve("demo/Box.java"), """
                package demo;
                public class Box {
                    private final int w;
                    public Box(int w) { this.w = w; }
                }
                """);
        Files.writeString(dir.resolve("demo/Factory.java"), """
                package demo;
                public class Factory {
                    public Point origin() { return new Point(0, 0); }
                    public Box unit() { return new Box(1); }
                }
                """);
        index.index("ctor", dir.toString());

        assertThat(impact.impactOf("ctor:demo/Point.java:demo.Point").callers())
                .contains("ctor:demo/Factory.java:demo.Factory#origin()");
        assertThat(impact.impactOf("ctor:demo/Box.java:demo.Box").callers())
                .contains("ctor:demo/Factory.java:demo.Factory#unit()");
    }

    @Test
    void aCallOnAnExternalTypeIsNotBoundToASameNamedRepoMethod(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Report.java"), """
                package demo;
                public class Report {
                    public static Report of(String row) { return new Report(); }
                }
                """);
        Files.writeString(dir.resolve("demo/Caller.java"), """
                package demo;
                public class Caller {
                    public Object external() { return java.util.List.of("a"); }
                    public Object externalShort() { return Map.of("a"); }
                    public Report internal() { return Report.of("x"); }
                }
                """);
        index.index("ext", dir.toString());

        assertThat(impact.impactOf("ext:demo/Report.java:demo.Report#of(String)").callers())
                .contains("ext:demo/Caller.java:demo.Caller#internal()")
                .noneMatch(c -> c.contains("#external"));
    }

    @Test
    void anUnqualifiedCallPrefersTheCallersOwnClass(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Alpha.java"), """
                package demo;
                public class Alpha {
                    static int of(String s) { return 1; }
                    int use() { return of("x"); }
                }
                """);
        Files.writeString(dir.resolve("demo/Beta.java"), """
                package demo;
                public class Beta {
                    static int of(String s) { return 2; }
                }
                """);
        index.index("own", dir.toString());

        assertThat(impact.impactOf("own:demo/Alpha.java:demo.Alpha#of(String)").callers())
                .contains("own:demo/Alpha.java:demo.Alpha#use()");
        assertThat(impact.impactOf("own:demo/Beta.java:demo.Beta#of(String)").callers()).isEmpty();
    }

    @Test
    void aCallWhoseArityMatchesNoCandidateIsLeftUnresolved(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Harness.java"), """
                package demo;
                public class Harness {
                    double score(String q, int k) { return 0; }
                }
                """);
        Files.writeString(dir.resolve("demo/Caller.java"), """
                package demo;
                public class Caller {
                    double read(java.util.OptionalDouble hit) { return hit.score(); }
                }
                """);
        index.index("ari", dir.toString());

        assertThat(impact.impactOf("ari:demo/Harness.java:demo.Harness#score(String,int)").callers()).isEmpty();
    }
}
