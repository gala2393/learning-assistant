package com.mytext.learningassistant.embedding;

import java.util.List;
import java.util.Optional;

public interface EmbeddingClient {

    Optional<List<Double>> embed(String text);
}
