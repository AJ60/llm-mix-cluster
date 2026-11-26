/**
 * Separates reasoning / thinking traces (`reasoning_content` or inline `<think>...</think>`)
 * cleanly from the final assistant response for both streaming and completed messages.
 */

export interface SplitReasoningResult {
  reasoning: string;
  answer: string;
  isThinkingOpen: boolean;
}

export function splitReasoningContent(
  rawContent: string,
  explicitReasoning = ''
): SplitReasoningResult {
  let reasoningParts: string[] = [];
  if (explicitReasoning.trim().length > 0) {
    reasoningParts.push(explicitReasoning.trim());
  }

  let remaining = rawContent;
  let isThinkingOpen = false;

  // Extract all closed <think>...</think> or <thought>...</thought> blocks
  const closedRegex = /<(?:think|thought)>([\s\S]*?)<\/(?:think|thought)>/gi;
  remaining = remaining.replace(closedRegex, (_match, inner: string) => {
    if (inner.trim().length > 0) {
      reasoningParts.push(inner.trim());
    }
    return '';
  });

  // Handle still-open streaming <think> tag before </think> arrives
  const openMatch = remaining.match(/<(?:think|thought)>([\s\S]*)$/i);
  if (openMatch) {
    isThinkingOpen = true;
    const partialThink = openMatch[1];
    if (partialThink.trim().length > 0) {
      reasoningParts.push(partialThink.trim());
    }
    remaining = remaining.slice(0, openMatch.index ?? 0);
  }

  return {
    reasoning: reasoningParts.join('\n\n').trim(),
    answer: remaining.trim(),
    isThinkingOpen,
  };
}
