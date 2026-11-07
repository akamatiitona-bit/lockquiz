package com.example.lockquiz

import kotlin.random.Random

class QuizSession(words: List<Word>, count: Int) {
    val questions: List<Question> = words.shuffled()
        .take(count.coerceAtLeast(1))
        .map { Question(it, if (Random.nextBoolean()) Direction.JA_TO_EN else Direction.EN_TO_JA) }

    var index = 0
        private set

    val current: Question get() = questions[index]
    val total: Int get() = questions.size
    val finished: Boolean get() = index >= questions.size

    fun advance() { index++ }
}
