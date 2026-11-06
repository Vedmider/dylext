package com.example.contextmcp.domain.port.out;

/** Counts tokens in text. The baseline impl estimates; a real tokenizer adapter can replace it. */
public interface Tokenizer {

    int count(String text);
}
