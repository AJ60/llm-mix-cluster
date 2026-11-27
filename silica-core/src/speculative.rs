//! Speculative decoding coordinator:
//! - Native MTP (`NextN`) detection
//! - Pure-Rust Prompt Lookup (N-gram) speculative token predictor
//! - External Draft Model (`--model-draft`) with strict GGUF vocab-hash verification

use crate::gguf::GgufModelCard;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SpeculativeMode {
    Off,
    NativeMtp,
    #[serde(alias = "prompt_lookup")]
    PromptLookupNgram,
    ExternalDraft,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DraftValidationResult {
    pub compatible: bool,
    pub main_vocab_hash: String,
    pub draft_vocab_hash: String,
    pub reason: String,
}

/// Validates that a draft GGUF model shares the exact tokenizer vocabulary hash of the main model.
pub fn validate_draft_vocab(
    main_card: &GgufModelCard,
    draft_card: &GgufModelCard,
) -> DraftValidationResult {
    let compatible = !main_card.vocab_hash.is_empty()
        && main_card.vocab_hash == draft_card.vocab_hash
        && main_card.vocab_size == draft_card.vocab_size;

    let reason = if compatible {
        format!(
            "Verified identical vocabulary hash ({}, {} tokens)",
            main_card.vocab_hash, main_card.vocab_size
        )
    } else {
        format!(
            "Vocab hash mismatch: main={} ({} tokens) vs draft={} ({} tokens) — draft model rejected",
            main_card.vocab_hash, main_card.vocab_size, draft_card.vocab_hash, draft_card.vocab_size
        )
    };

    DraftValidationResult {
        compatible,
        main_vocab_hash: main_card.vocab_hash.clone(),
        draft_vocab_hash: draft_card.vocab_hash.clone(),
        reason,
    }
}

/// Pure-Rust N-gram Prompt Lookup decoder: searches prompt history for the longest suffix match
/// and proposes up to `max_draft` continuation tokens with zero extra VRAM cost.
pub fn prompt_lookup_ngram(tokens: &[u32], ngram_size: usize, max_draft: usize) -> Vec<u32> {
    if ngram_size == 0 || max_draft == 0 || tokens.len() <= ngram_size {
        return Vec::new();
    }
    let needle = &tokens[tokens.len() - ngram_size..];
    let search_limit = tokens.len() - ngram_size;
    for pos in (0..search_limit).rev() {
        if &tokens[pos..pos + ngram_size] == needle {
            let start = pos + ngram_size;
            let end = (start + max_draft).min(tokens.len());
            if end > start {
                return tokens[start..end].to_vec();
            }
        }
    }
    Vec::new()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prompt_lookup_predicts_repeated_code_patterns() {
        let stream = vec![10, 20, 30, 40, 50, 60, 99, 10, 20, 30];
        let predicted = prompt_lookup_ngram(&stream, 3, 4);
        assert_eq!(predicted, vec![40, 50, 60, 99]);
    }
}
