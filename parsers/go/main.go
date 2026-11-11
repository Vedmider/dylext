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
	if fn.Type.Params != nil {
		var params []string
		for _, p := range fn.Type.Params.List {
			n := len(p.Names)
			if n == 0 {
				n = 1
			}
			for i := 0; i < n; i++ {
				params = append(params, exprString(p.Type))
			}
		}
		b.WriteString(strings.Join(params, ", "))
	}
	b.WriteString(")")
	return b.String()
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
				name = f.Sel.Name
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

func extract(source string) []symbol {
	fset := token.NewFileSet()
	file, err := parser.ParseFile(fset, "src.go", source, parser.ParseComments)
	if err != nil {
		return nil // let Dylext synthesize the MODULE fallback
	}
	out := []symbol{}
	for _, decl := range file.Decls {
		switch d := decl.(type) {
		case *ast.FuncDecl:
			path := d.Name.Name
			if recv := receiverType(d); recv != "" {
				path = recv + "#" + d.Name.Name
			}
			out = append(out, symbol{
				Kind: "METHOD", Name: d.Name.Name, SymbolPath: path,
				Signature:    funcSignature(fset, d),
				DocFirstLine: firstDocLine(d.Doc),
				StartLine:    fset.Position(d.Pos()).Line,
				EndLine:      fset.Position(d.End()).Line,
				CallTargets:  callTargets(d),
			})
		case *ast.GenDecl:
			for _, spec := range d.Specs {
				ts, ok := spec.(*ast.TypeSpec)
				if !ok {
					continue
				}
				kind, keyword := "CLASS", "type"
				switch ts.Type.(type) {
				case *ast.InterfaceType:
					kind = "INTERFACE"
				case *ast.StructType:
					kind = "CLASS"
				}
				out = append(out, symbol{
					Kind: kind, Name: ts.Name.Name, SymbolPath: ts.Name.Name,
					Signature:    keyword + " " + ts.Name.Name + " " + typeKeyword(ts.Type),
					DocFirstLine: firstDocLine(d.Doc),
					StartLine:    fset.Position(ts.Pos()).Line,
					EndLine:      fset.Position(ts.End()).Line,
					CallTargets:  []string{},
				})
			}
		}
	}
	return out
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
	syms := extract(req.Source)
	if syms == nil {
		syms = []symbol{} // Dylext synthesizes the MODULE fallback
	}
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
