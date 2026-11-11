// Dylext Go parser sidecar: a dumb, stdlib-only language parser.
// POST /parse {repo,filePath,language,source} -> neutral symbol JSON that Dylext's
// HttpSkeletonExtractor maps into its Symbol model. Parse only — no index/store/git.
// Replaceable by any service honouring the same contract (the Lego point).
//
// Neutral symbol kinds use Dylext's vocabulary: MODULE, CLASS, INTERFACE, METHOD, FIELD.
package main

import (
	"encoding/json"
	"go/ast"
	"go/parser"
	"go/token"
	"net/http"
	"os"
	"strings"
)

type symbol struct {
	Kind         string   `json:"kind"`
	Name         string   `json:"name"`
	SymbolPath   string   `json:"symbolPath"`
	Signature    string   `json:"signature"`
	DocFirstLine string   `json:"docFirstLine"`
	StartLine    int      `json:"startLine"`
	EndLine      int      `json:"endLine"`
	CallTargets  []string `json:"callTargets"`
	FqName       string   `json:"fqName,omitempty"`
	Receiver     string   `json:"receiver,omitempty"`
	ReturnType   string   `json:"returnType,omitempty"`
	Modifiers    []string `json:"modifiers,omitempty"`
	Imports      []string `json:"imports,omitempty"`
}

type parseRequest struct {
	Repo     string `json:"repo"`
	FilePath string `json:"filePath"`
	Language string `json:"language"`
	Source   string `json:"source"`
}

type parseResponse struct {
	Symbols []symbol `json:"symbols"`
	Error   string   `json:"error,omitempty"`
}

func firstDocLine(doc *ast.CommentGroup) string {
	if doc == nil {
		return ""
	}
	for _, line := range strings.Split(strings.TrimSpace(doc.Text()), "\n") {
		if s := strings.TrimSpace(line); s != "" {
			return s
		}
	}
	return ""
}

func receiverType(fn *ast.FuncDecl) string {
	if fn.Recv == nil || len(fn.Recv.List) == 0 {
		return ""
	}
	switch t := fn.Recv.List[0].Type.(type) {
	case *ast.StarExpr:
		if id, ok := t.X.(*ast.Ident); ok {
			return id.Name
		}
	case *ast.Ident:
		return t.Name
	}
	return ""
}

func funcSignature(fset *token.FileSet, fn *ast.FuncDecl) string {
	var b strings.Builder
	b.WriteString("func ")
	if recv := receiverType(fn); recv != "" {
		b.WriteString("(" + recv + ") ")
	}
	b.WriteString(fn.Name.Name + "(")
	b.WriteString(paramList(fn.Type.Params))
	b.WriteString(")")
	if ret := resultList(fn.Type.Results); ret != "" {
		b.WriteString(" " + ret)
	}
	return b.String()
}

func paramList(fields *ast.FieldList) string {
	if fields == nil {
		return ""
	}
	var params []string
	for _, p := range fields.List {
		typ := exprString(p.Type)
		if len(p.Names) == 0 {
			params = append(params, typ)
			continue
		}
		for _, n := range p.Names {
			params = append(params, n.Name+" "+typ)
		}
	}
	return strings.Join(params, ", ")
}

func resultList(fields *ast.FieldList) string {
	if fields == nil || len(fields.List) == 0 {
		return ""
	}
	var rs []string
	for _, r := range fields.List {
		rs = append(rs, exprString(r.Type))
	}
	if len(rs) == 1 && len(fields.List[0].Names) == 0 {
		return rs[0]
	}
	return "(" + strings.Join(rs, ", ") + ")"
}

func exprString(e ast.Expr) string {
	switch t := e.(type) {
	case *ast.Ident:
		return t.Name
	case *ast.StarExpr:
		return "*" + exprString(t.X)
	case *ast.SelectorExpr:
		return exprString(t.X) + "." + t.Sel.Name
	case *ast.ArrayType:
		return "[]" + exprString(t.Elt)
	case *ast.MapType:
		return "map[" + exprString(t.Key) + "]" + exprString(t.Value)
	case *ast.InterfaceType:
		return "interface{}"
	default:
		return "_"
	}
}

func callTargets(fn *ast.FuncDecl) []string {
	seen := map[string]bool{}
	var out []string
	ast.Inspect(fn, func(n ast.Node) bool {
		if call, ok := n.(*ast.CallExpr); ok {
			var name string
			switch f := call.Fun.(type) {
			case *ast.Ident:
				name = f.Name
			case *ast.SelectorExpr:
				if base, ok := f.X.(*ast.Ident); ok {
					name = base.Name + "." + f.Sel.Name // qualified: pkg.Func / recv.Method
				} else {
					name = f.Sel.Name
				}
			}
			if name != "" && !seen[name] {
				seen[name] = true
				out = append(out, name)
			}
		}
		return true
	})
	if out == nil {
		out = []string{}
	}
	return out
}

func exported(name string) []string {
	if name != "" && name[0] >= 'A' && name[0] <= 'Z' {
		return []string{"exported"}
	}
	return []string{}
}

func structFields(typeName string, st *ast.StructType, fset *token.FileSet) []symbol {
	var out []symbol
	if st.Fields == nil {
		return out
	}
	for _, f := range st.Fields.List {
		typ := exprString(f.Type)
		for _, n := range f.Names {
			out = append(out, symbol{
				Kind: "FIELD", Name: n.Name, SymbolPath: typeName + "." + n.Name,
				Signature: n.Name + " " + typ, Receiver: typeName, ReturnType: typ,
				StartLine: fset.Position(n.Pos()).Line, EndLine: fset.Position(n.End()).Line,
				CallTargets: []string{}, Modifiers: exported(n.Name),
			})
		}
	}
	return out
}

func interfaceMethods(typeName string, it *ast.InterfaceType, fset *token.FileSet) []symbol {
	var out []symbol
	if it.Methods == nil {
		return out
	}
	for _, m := range it.Methods.List {
		ft, ok := m.Type.(*ast.FuncType)
		if !ok || len(m.Names) == 0 {
			continue
		}
		name := m.Names[0].Name
		sig := name + "(" + paramList(ft.Params) + ")"
		ret := resultList(ft.Results)
		if ret != "" {
			sig += " " + ret
		}
		out = append(out, symbol{
			Kind: "METHOD", Name: name, SymbolPath: typeName + "#" + name,
			Signature: sig, Receiver: typeName, ReturnType: ret,
			StartLine: fset.Position(m.Pos()).Line, EndLine: fset.Position(m.End()).Line,
			CallTargets: []string{}, Modifiers: exported(name),
		})
	}
	return out
}

func extract(source string) []symbol {
	fset := token.NewFileSet()
	file, err := parser.ParseFile(fset, "src.go", source, parser.ParseComments)
	if err != nil {
		return nil // let Dylext synthesize the MODULE fallback
	}
	pkg := file.Name.Name
	fq := func(path string) string {
		if pkg == "" {
			return path
		}
		return pkg + "." + path
	}
	out := []symbol{}
	for _, decl := range file.Decls {
		switch d := decl.(type) {
		case *ast.FuncDecl:
			path := d.Name.Name
			recv := receiverType(d)
			if recv != "" {
				path = recv + "#" + d.Name.Name
			}
			out = append(out, symbol{
				Kind: "METHOD", Name: d.Name.Name, SymbolPath: path,
				Signature:    funcSignature(fset, d),
				DocFirstLine: firstDocLine(d.Doc),
				StartLine:    fset.Position(d.Pos()).Line,
				EndLine:      fset.Position(d.End()).Line,
				CallTargets:  callTargets(d),
				FqName:       fq(path), Receiver: recv,
				ReturnType: resultList(d.Type.Results), Modifiers: exported(d.Name.Name),
			})
		case *ast.GenDecl:
			out = append(out, genDeclSymbols(d, fset, fq)...)
		}
	}
	return out
}

func genDeclSymbols(d *ast.GenDecl, fset *token.FileSet, fq func(string) string) []symbol {
	var out []symbol
	for _, spec := range d.Specs {
		switch s := spec.(type) {
		case *ast.TypeSpec:
			kind := "CLASS"
			if _, ok := s.Type.(*ast.InterfaceType); ok {
				kind = "INTERFACE"
			}
			out = append(out, symbol{
				Kind: kind, Name: s.Name.Name, SymbolPath: s.Name.Name,
				Signature:    "type " + s.Name.Name + " " + typeKeyword(s.Type),
				DocFirstLine: firstDocLine(d.Doc),
				StartLine:    fset.Position(s.Pos()).Line,
				EndLine:      fset.Position(s.End()).Line,
				CallTargets:  []string{}, FqName: fq(s.Name.Name), Modifiers: exported(s.Name.Name),
			})
			if st, ok := s.Type.(*ast.StructType); ok {
				out = append(out, structFields(s.Name.Name, st, fset)...)
			}
			if it, ok := s.Type.(*ast.InterfaceType); ok {
				out = append(out, interfaceMethods(s.Name.Name, it, fset)...)
			}
		case *ast.ValueSpec:
			mods := []string{}
			if d.Tok.String() == "const" {
				mods = append(mods, "const")
			}
			for _, n := range s.Names {
				if n.Name == "_" {
					continue
				}
				out = append(out, symbol{
					Kind: "FIELD", Name: n.Name, SymbolPath: n.Name,
					Signature:    d.Tok.String() + " " + n.Name,
					DocFirstLine: firstDocLine(d.Doc),
					StartLine:    fset.Position(n.Pos()).Line,
					EndLine:      fset.Position(n.End()).Line,
					CallTargets:  []string{}, FqName: fq(n.Name),
					Modifiers: append(exported(n.Name), mods...),
				})
			}
		}
	}
	return out
}

func moduleImports(file *ast.File) []string {
	out := []string{}
	for _, imp := range file.Imports {
		path := strings.Trim(imp.Path.Value, `"`)
		if imp.Name != nil {
			out = append(out, imp.Name.Name+" "+path)
		} else {
			out = append(out, path)
		}
	}
	return out
}

func buildSymbols(source, filePath string) []symbol {
	fset := token.NewFileSet()
	file, err := parser.ParseFile(fset, "src.go", source, parser.ParseComments)
	members := extract(source)
	if err != nil || members == nil {
		return []symbol{}
	}
	name := filePath
	if i := strings.LastIndex(name, "/"); i >= 0 {
		name = name[i+1:]
	}
	if name == "" {
		name = "<module>"
	}
	end := 1
	for _, m := range members {
		if m.EndLine > end {
			end = m.EndLine
		}
	}
	module := symbol{
		Kind: "MODULE", Name: name, SymbolPath: "<module>", Signature: name,
		StartLine: 1, EndLine: end, CallTargets: []string{}, Imports: moduleImports(file),
	}
	return append([]symbol{module}, members...)
}

func typeKeyword(e ast.Expr) string {
	switch e.(type) {
	case *ast.InterfaceType:
		return "interface"
	case *ast.StructType:
		return "struct"
	default:
		return ""
	}
}

func parseHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("content-type", "application/json")
	var req parseRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		_ = json.NewEncoder(w).Encode(parseResponse{Symbols: []symbol{}, Error: err.Error()})
		return
	}
	syms := buildSymbols(req.Source, req.FilePath)
	_ = json.NewEncoder(w).Encode(parseResponse{Symbols: syms})
}

func main() {
	http.HandleFunc("/parse", parseHandler)
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("content-type", "application/json")
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	})
	port := os.Getenv("PORT")
	if port == "" {
		port = "9092"
	}
	_ = http.ListenAndServe(":"+port, nil)
}
