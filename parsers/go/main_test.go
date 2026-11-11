package main

import (
	"encoding/json"
	"go/parser"
	"go/token"
	"os"
	"strings"
	"testing"
)

func find(syms []symbol, name string) *symbol {
	for i := range syms {
		if syms[i].Name == name {
			return &syms[i]
		}
	}
	return nil
}

func TestPlainFunction(t *testing.T) {
	syms := extract("package p\n\n// Greet greets.\nfunc Greet(name string) string { return hi(name) }\n")
	fn := find(syms, "Greet")
	if fn == nil {
		t.Fatal("Greet not found")
	}
	if fn.Kind != "METHOD" || fn.SymbolPath != "Greet" {
		t.Fatalf("got kind=%s path=%s", fn.Kind, fn.SymbolPath)
	}
	if fn.Signature != "func Greet(name string) string" {
		t.Fatalf("signature: %q", fn.Signature)
	}
	if fn.DocFirstLine != "Greet greets." {
		t.Fatalf("doc: %q", fn.DocFirstLine)
	}
	if len(fn.CallTargets) == 0 || fn.CallTargets[0] != "hi" {
		t.Fatalf("callTargets: %v", fn.CallTargets)
	}
}

func TestReceiverMethodPath(t *testing.T) {
	syms := extract("package p\ntype Server struct{}\nfunc (s *Server) Start(port int) {}\n")
	m := find(syms, "Start")
	if m == nil || m.SymbolPath != "Server#Start" {
		t.Fatalf("receiver method path wrong: %+v", m)
	}
	srv := find(syms, "Server")
	if srv == nil || srv.Kind != "CLASS" {
		t.Fatalf("struct should be CLASS: %+v", srv)
	}
}

func TestInterfaceKind(t *testing.T) {
	syms := extract("package p\ntype Reader interface { Read() int }\n")
	r := find(syms, "Reader")
	if r == nil || r.Kind != "INTERFACE" {
		t.Fatalf("interface kind wrong: %+v", r)
	}
}

func TestSyntaxErrorReturnsNil(t *testing.T) {
	if extract("package p\nfunc (") != nil {
		t.Fatal("expected nil on parse error")
	}
}

func TestEmptyPackageHasNoSymbols(t *testing.T) {
	if len(extract("package p\n")) != 0 {
		t.Fatal("expected no symbols")
	}
}

type expectedSymbol struct {
	Kind, Name, SymbolPath, Signature, DocFirstLine, FqName, Receiver, ReturnType string
	Modifiers, CallTargets                                                        []string
}

func TestConformanceFixture(t *testing.T) {
	src, err := os.ReadFile("../conformance/sample.go")
	if err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile("../conformance/sample.go.expected.json")
	if err != nil {
		t.Fatal(err)
	}
	var exp struct {
		Symbols       []map[string]interface{} `json:"symbols"`
		ModuleImports []string                 `json:"moduleImports"`
	}
	if err := json.Unmarshal(raw, &exp); err != nil {
		t.Fatal(err)
	}
	got := map[string]symbol{}
	for _, s := range extract(string(src)) {
		got[s.SymbolPath] = s
	}
	for _, e := range exp.Symbols {
		path := e["symbolPath"].(string)
		s, ok := got[path]
		if !ok {
			t.Fatalf("missing symbol %s", path)
		}
		check := func(field, want, have string) {
			if want != "" && want != have {
				t.Errorf("%s.%s: want %q got %q", path, field, want, have)
			}
		}
		check("kind", str2(e, "kind"), s.Kind)
		check("signature", str2(e, "signature"), s.Signature)
		check("docFirstLine", str2(e, "docFirstLine"), s.DocFirstLine)
		check("fqName", str2(e, "fqName"), s.FqName)
		check("receiver", str2(e, "receiver"), s.Receiver)
		check("returnType", str2(e, "returnType"), s.ReturnType)
		if want := strSlice(e, "callTargets"); want != nil {
			if strings.Join(want, ",") != strings.Join(s.CallTargets, ",") {
				t.Errorf("%s.callTargets: want %v got %v", path, want, s.CallTargets)
			}
		}
		if want := strSlice(e, "modifiers"); want != nil {
			if strings.Join(want, ",") != strings.Join(s.Modifiers, ",") {
				t.Errorf("%s.modifiers: want %v got %v", path, want, s.Modifiers)
			}
		}
	}
	// module imports
	fset := token.NewFileSet()
	file, _ := parser.ParseFile(fset, "src.go", string(src), parser.ParseComments)
	if got := strings.Join(moduleImports(file), ","); got != strings.Join(exp.ModuleImports, ",") {
		t.Errorf("moduleImports: want %v got %v", exp.ModuleImports, moduleImports(file))
	}
}

func str2(m map[string]interface{}, k string) string {
	if v, ok := m[k].(string); ok {
		return v
	}
	return ""
}

func strSlice(m map[string]interface{}, k string) []string {
	v, ok := m[k].([]interface{})
	if !ok {
		return nil
	}
	out := []string{}
	for _, e := range v {
		out = append(out, e.(string))
	}
	return out
}
