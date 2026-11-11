package sample

import (
	"fmt"
	str "strings"
)

// Greeter greets people.
type Greeter struct {
	Prefix string
}

// Shouter is a loud greeter.
type Shouter interface {
	Shout(msg string) string
}

const MaxLen = 10

// Hello returns a greeting.
func (g *Greeter) Hello(name string) string {
	return g.Prefix + str.ToUpper(name) + fmt.Sprint("!")
}

func Plain(a int, b int) int {
	return a + b
}
