package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.CodeEdge;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Call targets "[recv.]name[/arity]" parse into precise edges (name + receiver + call-site arity). */
class IndexServiceSimpleNameTest {

    private static CodeEdge e(String target) {
        return IndexService.toEdge("r", "r:F.java:F#m()", target);
    }

    @Test
    void qualifiedTargetsSplitIntoNameAndReceiver() {
        assertThat(e("str.ToUpper").dstName()).isEqualTo("ToUpper");
        assertThat(e("str.ToUpper").dstRecv()).isEqualTo("str");
        assertThat(e("a.b.c.deep").dstName()).isEqualTo("deep");
        assertThat(e("a.b.c.deep").dstRecv()).isEqualTo("a.b.c");
    }

    @Test
    void bareTargetsHaveNoReceiver() {
        assertThat(e("searchSymbols").dstName()).isEqualTo("searchSymbols");
        assertThat(e("searchSymbols").dstRecv()).isNull();
    }

    @Test
    void aritySuffixIsParsedOutOfTheTarget() {
        assertThat(e("greet/2").dstName()).isEqualTo("greet");
        assertThat(e("greet/2").dstArity()).isEqualTo(2);
        assertThat(e("recv.method/3").dstName()).isEqualTo("method");
        assertThat(e("recv.method/3").dstRecv()).isEqualTo("recv");
        assertThat(e("recv.method/3").dstArity()).isEqualTo(3);
        assertThat(e("noArity").dstArity()).isNull();
    }

    @Test
    void inferredParamTypesSuffixIsParsedOutOfTheTarget() {
        assertThat(e("greet/1#String").dstName()).isEqualTo("greet");
        assertThat(e("greet/1#String").dstArity()).isEqualTo(1);
        assertThat(e("greet/1#String").dstParamTypes()).isEqualTo("String");
        assertThat(e("pair/2#String,int").dstParamTypes()).isEqualTo("String,int");
        assertThat(e("recv.m/1#Thing").dstRecv()).isEqualTo("recv");
        assertThat(e("recv.m/1#Thing").dstParamTypes()).isEqualTo("Thing");
        assertThat(e("greet/1").dstParamTypes()).isNull();
    }
}
