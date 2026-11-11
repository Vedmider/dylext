package main

import "testing"

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
	if fn.Signature != "func Greet(string)" {
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
