package com.github.x0x0b.codexlauncher.settings.options

/**
 * Model selection for the `--model` argument.
 * 
 * This enum supports both GPT-5 and GPT-5.1 model families:
 * - GPT_5* models are maintained for compatibility with enterprise environments
 *   where gpt-5.1 models may not yet be available.
 * - GPT_5_1* models are provided as additional options and should not replace
 *   GPT_5* models in enterprise deployments.
 */
enum class Model {
    /** Do not pass --model. */
    DEFAULT,
    GPT_5,
    GPT_5_CODEX,
    CODEX_MINI_LATEST,
    GPT_5_1,
    GPT_5_1_CODEX,
    GPT_5_1_CODEX_MINI,
    /** Use customModel from settings. */
    CUSTOM;

    fun cliName(): String = when (this) {
        DEFAULT -> ""
        GPT_5 -> "gpt-5"
        GPT_5_CODEX -> "gpt-5-codex"
        CODEX_MINI_LATEST -> "codex-mini-latest"
        GPT_5_1 -> "gpt-5.1"
        GPT_5_1_CODEX -> "gpt-5.1-codex"
        GPT_5_1_CODEX_MINI -> "gpt-5.1-codex-mini"
        CUSTOM -> ""
    }

    fun toDisplayName(): String = when (this) {
        DEFAULT -> "Default"
        GPT_5 -> "gpt-5"
        GPT_5_CODEX -> "gpt-5-codex"
        CODEX_MINI_LATEST -> "codex-mini-latest"
        GPT_5_1 -> "gpt-5.1"
        GPT_5_1_CODEX -> "gpt-5.1-codex"
        GPT_5_1_CODEX_MINI -> "gpt-5.1-codex-mini"
        CUSTOM -> "Custom..."
    }

    override fun toString(): String = toDisplayName()
}
