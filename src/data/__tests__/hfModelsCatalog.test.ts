import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  CURATED_HF_GGUF_CATALOG,
  inferHfModelMetadata,
} from '../hfModelsCatalog.ts';

describe('hfModelsCatalog', () => {
  it('provides verified curated catalog models with non-empty fields', () => {
    assert.ok(CURATED_HF_GGUF_CATALOG.length >= 4);
    for (const item of CURATED_HF_GGUF_CATALOG) {
      assert.ok(item.id.length > 0);
      assert.ok(item.name.length > 0);
      assert.ok(item.hfRepo.includes('/'));
      assert.ok(item.totalParamsB > 0);
      assert.ok(item.activeParamsB > 0);
      assert.ok(item.activeParamsB <= item.totalParamsB);
      assert.ok(item.layers > 0);
      assert.ok(item.sizeMb > 0);
      assert.ok(Array.isArray(item.quantOptions));
      assert.ok(item.quantOptions.length >= 1);
    }
  });

  it('correctly infers metadata from raw Hugging Face Hub model responses', () => {
    const rawHfMoE = {
      id: 'bartowski/DeepSeek-V3-GGUF',
      downloads: 125000,
      likes: 850,
      tags: ['gguf', 'moe', 'deepseek'],
    };

    const inferred = inferHfModelMetadata(rawHfMoE);
    assert.equal(inferred.architecture, 'MoE');
    assert.equal(inferred.author, 'bartowski');
    assert.ok(inferred.activeParamsB < inferred.totalParamsB, 'MoE must have active < total params');
    assert.ok(inferred.quantOptions.length >= 2);
  });

  it('handles standard Dense model tags and names accurately', () => {
    const rawHfDense = {
      id: 'Qwen/Qwen2.5-7B-Instruct-GGUF',
      downloads: 350000,
      likes: 1200,
      tags: ['gguf', 'chat'],
    };

    const inferred = inferHfModelMetadata(rawHfDense);
    assert.equal(inferred.architecture, 'Dense');
    assert.equal(inferred.totalParamsB, 7.0);
    assert.equal(inferred.activeParamsB, 7.0);
  });
});
