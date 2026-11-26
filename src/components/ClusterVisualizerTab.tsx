import React, { useMemo, useState } from 'react';
import {
  Activity,
  Database,
  Layers,
  Network,
  RefreshCw,
  Sparkles,
} from 'lucide-react';
import { GgufModelPreset, PlannedStarNode, StarClusterPlan } from '../types/starCluster';

interface ClusterVisualizerTabProps {
  plan: StarClusterPlan;
  activeModel: GgufModelPreset;
  onRefresh?: () => void;
  onAutoBalance?: () => void;
  onStopAndFree?: () => void;
}

const NODE_COLORS = [
  { stroke: '#10B981', fill: '#059669', badge: 'bg-emerald-500/20 text-emerald-400 border-emerald-500/30' },
  { stroke: '#0EA5E9', fill: '#0284C7', badge: 'bg-sky-500/20 text-sky-400 border-sky-500/30' },
  { stroke: '#8B5CF6', fill: '#7C3AED', badge: 'bg-violet-500/20 text-violet-400 border-violet-500/30' },
  { stroke: '#F59E0B', fill: '#D97706', badge: 'bg-amber-500/20 text-amber-400 border-amber-500/30' },
];

function formatMem(mb: number): string {
  if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
  return `${Math.round(mb)} MB`;
}

export const ClusterVisualizerTab: React.FC<ClusterVisualizerTabProps> = ({
  plan,
  activeModel,
  onRefresh,
  onAutoBalance,
  onStopAndFree,
}) => {
  const [expandedTensorIndex, setExpandedTensorIndex] = useState<boolean>(false);

  const master = plan.nodes.find((n) => n.role === 'Master') ?? plan.nodes[0];
  const rawWorkers = plan.nodes.filter(
    (n) => n.role === 'Worker' && n.isConnected && (n.allocatedLayers ?? 0) > 0
  );
  const standbyWorkers = plan.nodes.filter(
    (n) => n.role === 'Worker' && n.isConnected && (n.allocatedLayers ?? 0) === 0
  );

  const workers: PlannedStarNode[] = rawWorkers;

  const activeNodes = useMemo(() => {
    return [master, ...workers].filter(Boolean);
  }, [master, workers]);

  const totalLayers = Math.max(1, activeModel.layers || 28);

  // Per-tensor mock inspection profiles for the model layers
  const inspectedTensors = useMemo(() => {
    const items: Array<{
      name: string;
      shape: string;
      quant: string;
      sizeMb: number;
      nodeLabel: string;
      residency: 'RAM' | 'VRAM' | 'Flash Cache';
    }> = [];

    // Master embeddings
    items.push({
      name: 'token_embd.weight',
      shape: `[${activeModel.embeddingLength || 4096}, ${activeModel.vocabSize || 151936}]`,
      quant: activeModel.quant || 'Q4_K_M',
      sizeMb: Math.round(activeModel.sizeMb * 0.08),
      nodeLabel: master?.label || 'Master Host',
      residency: master?.safeVramMb > 0 ? 'VRAM' : 'RAM',
    });

    // Sample layers across nodes
    for (const node of activeNodes) {
      if (node.allocatedLayers <= 0) continue;
      const sampleLayer = node.layerStart;
      const isCached = node.platform === 'Android';
      items.push({
        name: `blk.${sampleLayer}.attn_q.weight`,
        shape: `[${activeModel.embeddingLength || 4096}, ${activeModel.embeddingLength || 4096}]`,
        quant: activeModel.quant || 'Q4_K_M',
        sizeMb: Math.round((node.allocatedMb / Math.max(1, node.allocatedLayers)) * 0.25),
        nodeLabel: node.label,
        residency: isCached ? 'Flash Cache' : node.safeVramMb > 0 ? 'VRAM' : 'RAM',
      });
      items.push({
        name: `blk.${sampleLayer}.ffn_gate.weight`,
        shape: `[${activeModel.embeddingLength || 4096}, 11008]`,
        quant: activeModel.quant || 'Q4_K_M',
        sizeMb: Math.round((node.allocatedMb / Math.max(1, node.allocatedLayers)) * 0.45),
        nodeLabel: node.label,
        residency: isCached ? 'Flash Cache' : node.safeVramMb > 0 ? 'VRAM' : 'RAM',
      });
    }

    // Output lm_head
    items.push({
      name: 'output.weight',
      shape: `[${activeModel.embeddingLength || 4096}, ${activeModel.vocabSize || 151936}]`,
      quant: activeModel.quant || 'Q4_K_M',
      sizeMb: Math.round(activeModel.sizeMb * 0.08),
      nodeLabel: master?.label || 'Master Host',
      residency: master?.safeVramMb > 0 ? 'VRAM' : 'RAM',
    });

    return items;
  }, [activeModel, activeNodes, master]);

  // Topology SVG Dimensions
  const svgW = 860;
  const svgH = Math.max(220, workers.length * 90 + 40);
  const masterX = 122;
  const masterY = svgH / 2;
  const workerX = 738;

  return (
    <div className="space-y-4 max-w-6xl mx-auto">
      {/* Top Action Bar */}
      <div className="flex flex-wrap items-center justify-between gap-3 p-3.5 rounded-xl border border-slate-800 bg-[#0F172A] shadow-xs">
        <div className="flex items-center gap-3">
          <div className="p-2 rounded-lg bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
            <Network className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-sm font-semibold text-slate-100 flex items-center gap-2">
              <span>Cluster Visualizer & Topology</span>
              <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-slate-800 text-slate-300 border border-slate-700">
                {activeNodes.length} Active Nodes
              </span>
            </h2>
            <p className="text-xs text-slate-400">
              Proportional layer sharding across Master VRAM and Android Flash/RAM nodes
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {onRefresh && (
            <button
              type="button"
              onClick={onRefresh}
              className="px-2.5 py-1.5 rounded-lg border border-slate-700 bg-slate-800/80 hover:bg-slate-700 text-slate-300 text-xs font-medium inline-flex items-center gap-1.5 transition-colors"
            >
              <RefreshCw className="w-3.5 h-3.5" />
              <span>Probe</span>
            </button>
          )}
          {onAutoBalance && (
            <button
              type="button"
              onClick={onAutoBalance}
              className="px-3 py-1.5 rounded-lg border border-emerald-500/40 bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 text-xs font-medium inline-flex items-center gap-1.5 transition-colors"
            >
              <Sparkles className="w-3.5 h-3.5" />
              <span>Auto-Balance</span>
            </button>
          )}
          {onStopAndFree && (
            <button
              type="button"
              onClick={onStopAndFree}
              className="px-3 py-1.5 rounded-lg border border-rose-500/40 bg-rose-500/10 hover:bg-rose-500/20 text-rose-300 text-xs font-medium transition-colors"
            >
              Stop & Free RAM
            </button>
          )}
        </div>
      </div>

      {standbyWorkers.length > 0 && (
        <div className="px-4 py-2.5 rounded-xl border border-amber-500/30 bg-amber-500/10 text-amber-200 text-xs flex items-center justify-between gap-3">
          <span>
            <strong>Standby / Fallback:</strong> {standbyWorkers.map((w) => `${w.label} (${w.statusNote || '0L assigned'})`).join(', ')}
          </span>
          {onAutoBalance && (
            <button
              type="button"
              onClick={onAutoBalance}
              className="px-2.5 py-1 rounded-md bg-amber-500/20 hover:bg-amber-500/30 text-amber-100 font-medium shrink-0"
            >
              Re-Attach RPC
            </button>
          )}
        </div>
      )}

      {/* Topology Canvas Section */}
      <div className="rounded-xl border border-slate-800 bg-[#0B0F17] p-4 overflow-x-auto shadow-inner">
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-mono uppercase tracking-wider text-slate-400 font-semibold flex items-center gap-1.5">
            <Activity className="w-3.5 h-3.5 text-emerald-400" />
            Live Star Topology
          </span>
          <span className="text-[11px] font-mono text-slate-400">
            Speed: <strong className="text-emerald-400">~{plan.estimatedClusterTokPerSec} tok/s</strong> · Fit: <strong className="text-sky-400">{plan.fitVerdict}</strong>
          </span>
        </div>

        <svg viewBox={`0 0 ${svgW} ${svgH}`} className="w-full h-auto min-w-[700px] select-none">
          <defs>
            <linearGradient id="linkGradientGreen" x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%" stopColor="#10B981" stopOpacity="0.8" />
              <stop offset="100%" stopColor="#059669" stopOpacity="0.2" />
            </linearGradient>
            <linearGradient id="linkGradientYellow" x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%" stopColor="#F59E0B" stopOpacity="0.8" />
              <stop offset="100%" stopColor="#D97706" stopOpacity="0.2" />
            </linearGradient>
            <filter id="glow">
              <feGaussianBlur stdDeviation="3" result="coloredBlur" />
              <feMerge>
                <feMergeNode in="coloredBlur" />
                <feMergeNode in="SourceGraphic" />
              </feMerge>
            </filter>
          </defs>

          {/* Links between Master and Workers */}
          {workers.map((w, idx) => {
            const startY = (svgH - (workers.length * 80)) / 2 + 40;
            const curY = workers.length === 1 ? svgH / 2 : startY + idx * 80;
            const isHighLatency = (w.rttMs || 0) > 25;
            const strokeColor = isHighLatency ? '#F59E0B' : '#10B981';

            return (
              <g key={w.nodeId}>
                {/* Glowing curved connector line */}
                <path
                  d={`M ${masterX + 102} ${masterY} C ${(masterX + workerX) / 2} ${masterY}, ${(masterX + workerX) / 2} ${curY}, ${workerX - 102} ${curY}`}
                  fill="none"
                  stroke={strokeColor}
                  strokeWidth="2.5"
                  strokeDasharray="6 3"
                  filter="url(#glow)"
                  className="animate-pulse opacity-80"
                />

                {/* Link RTT badge at midpoint */}
                <g transform={`translate(${(masterX + workerX) / 2 - 35}, ${(masterY + curY) / 2 - 12})`}>
                  <rect width="70" height="22" rx="11" fill="#0D1524" stroke={strokeColor} strokeWidth="1" />
                  <text
                    x="35"
                    y="15"
                    textAnchor="middle"
                    fill={strokeColor}
                    fontSize="10"
                    fontFamily="monospace"
                    fontWeight="bold"
                  >
                    {w.rttMs || 0.5}ms · USB
                  </text>
                </g>

                {/* Worker Node Card */}
                <g
                  transform={`translate(${workerX - 102}, ${curY - 32})`}
                  className="cursor-default"
                >
                  <rect
                    width="204"
                    height="64"
                    rx="10"
                    fill="#111827"
                    stroke={NODE_COLORS[(idx + 1) % NODE_COLORS.length].stroke}
                    strokeWidth="1.5"
                    className="hover:fill-[#1F2937] transition-colors"
                  />
                  <text x="12" y="22" fill="#E2E8F0" fontSize="11" fontWeight="600">
                    {w.label.length > 23 ? `${w.label.slice(0, 22)}…` : w.label}
                  </text>
                  <text x="12" y="38" fill="#94A3B8" fontSize="10" fontFamily="monospace">
                    L{w.layerStart}–L{w.layerEnd} ({w.allocatedLayers}L)
                  </text>
                  <text x="12" y="52" fill="#38BDF8" fontSize="9" fontFamily="monospace">
                    RAM: {formatMem(w.allocatedMb)} / {formatMem(w.safeRamMb)}
                  </text>
                </g>
              </g>
            );
          })}

          {/* Master Node Card */}
          <g
            transform={`translate(${masterX - 102}, ${masterY - 38})`}
            className="cursor-default"
          >
            <rect
              width="204"
              height="76"
              rx="12"
              fill="#0F172A"
              stroke="#10B981"
              strokeWidth="2"
              filter="url(#glow)"
            />
            <text x="14" y="24" fill="#34D399" fontSize="12" fontWeight="bold">
              ★ {master.label.length > 21 ? `${master.label.slice(0, 20)}…` : master.label}
            </text>
            <text x="14" y="42" fill="#94A3B8" fontSize="10" fontFamily="monospace">
              L{master.layerStart}–L{master.layerEnd} ({master.allocatedLayers}L) · Host
            </text>
            <text x="14" y="56" fill="#38BDF8" fontSize="9" fontFamily="monospace">
              VRAM: {formatMem(master.safeVramMb)} (Offload)
            </text>
            <text x="14" y="68" fill="#A78BFA" fontSize="9" fontFamily="monospace">
              Allocated: {formatMem(master.allocatedMb)}
            </text>
          </g>
        </svg>
      </div>

      {/* Layer Sharding Timeline */}
      <div className="rounded-xl border border-slate-800 bg-[#0B0F17] p-4 space-y-2">
        <div className="flex items-center justify-between text-xs font-mono">
          <span className="text-slate-300 font-semibold flex items-center gap-1.5">
            <Layers className="w-3.5 h-3.5 text-sky-400" />
            Contiguous Layer Sharding Allocation
          </span>
          <span className="text-slate-400">
            Total Model Layers: <strong className="text-slate-200">{totalLayers}</strong> (L0 – L{totalLayers - 1})
          </span>
        </div>

        {/* Timeline Bar */}
        <div className="h-9 w-full rounded-lg bg-slate-900 border border-slate-800 overflow-hidden flex p-0.5 gap-0.5">
          {activeNodes.map((node, i) => {
            const pct = Math.max(4, (node.allocatedLayers / totalLayers) * 100);
            const color = NODE_COLORS[i % NODE_COLORS.length];
            return (
              <div
                key={node.nodeId}
                title={`${node.label}: L${node.layerStart}–L${node.layerEnd} (${node.allocatedLayers} layers, ${formatMem(node.allocatedMb)})`}
                className="h-full rounded-md flex items-center justify-center px-1.5 transition-all relative group cursor-pointer"
                style={{
                  width: `${pct}%`,
                  backgroundColor: color.stroke,
                }}
              >
                <span className="text-[10px] font-mono font-bold text-white truncate drop-shadow-xs">
                  {node.role === 'Master' ? 'Host' : `W${i}`}: L{node.layerStart}–L{node.layerEnd}
                </span>
              </div>
            );
          })}
        </div>

        {/* Timeline Legend */}
        <div className="flex flex-wrap items-center gap-4 pt-1 text-[11px] font-mono text-slate-400">
          {activeNodes.map((node, i) => {
            const color = NODE_COLORS[i % NODE_COLORS.length];
            return (
              <div key={node.nodeId} className="flex items-center gap-1.5">
                <div className="w-2.5 h-2.5 rounded-xs" style={{ backgroundColor: color.stroke }} />
                <span className="text-slate-300 font-medium">{node.label}</span>
                <span className="text-slate-500">
                  ({node.allocatedLayers}L · {((node.allocatedLayers / totalLayers) * 100).toFixed(0)}%)
                </span>
              </div>
            );
          })}
        </div>
      </div>

      {/* VRAM / RAM Usage Gauges Grid */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        {activeNodes.map((node, i) => {
          const cap = node.safeRamMb + node.safeVramMb || 1;
          const used = node.allocatedMb || 0;
          const pct = Math.min(100, Math.round((used / cap) * 100));
          const color = NODE_COLORS[i % NODE_COLORS.length];

          return (
            <div
              key={node.nodeId}
              className="p-3.5 rounded-xl border border-slate-800 bg-[#0F172A] space-y-2.5"
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="w-2 h-2 rounded-full" style={{ backgroundColor: color.stroke }} />
                  <span className="text-xs font-semibold text-slate-200 truncate max-w-[150px]">
                    {node.label}
                  </span>
                </div>
                <span className="text-[10px] font-mono px-1.5 py-0.5 rounded-md bg-slate-800 text-slate-300 border border-slate-700">
                  {node.role}
                </span>
              </div>

              {/* Progress Bar */}
              <div className="space-y-1">
                <div className="flex items-center justify-between text-[11px] font-mono">
                  <span className="text-slate-400">Safety Cap Usage</span>
                  <span className="text-slate-200 font-semibold">{pct}%</span>
                </div>
                <div className="h-2 w-full rounded-full bg-slate-800 overflow-hidden">
                  <div
                    className="h-full rounded-full transition-all duration-500"
                    style={{
                      width: `${pct}%`,
                      backgroundColor: pct > 85 ? '#EF4444' : color.stroke,
                    }}
                  />
                </div>
                <div className="flex items-center justify-between text-[10px] font-mono text-slate-400 pt-0.5">
                  <span>Allocated: {formatMem(used)}</span>
                  <span>Cap: {formatMem(cap)}</span>
                </div>
              </div>

              {/* Node Telemetry Details */}
              <div className="pt-2 border-t border-slate-800/80 grid grid-cols-2 gap-2 text-[10px] font-mono text-slate-400">
                <div>
                  <span className="text-slate-500 block">Link</span>
                  <span className="text-slate-300 font-medium">{node.linkType}</span>
                </div>
                <div>
                  <span className="text-slate-500 block">Sharded</span>
                  <span className="text-slate-300 font-medium">{node.allocatedLayers} Layers</span>
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {/* Per-Tensor Inspector Card */}
      <div className="rounded-xl border border-slate-800 bg-[#0B0F17] overflow-hidden">
        <button
          type="button"
          onClick={() => setExpandedTensorIndex((v) => !v)}
          className="w-full p-3.5 flex items-center justify-between bg-[#0F172A] hover:bg-[#152033] transition-colors text-left"
        >
          <div className="flex items-center gap-2 text-xs font-semibold text-slate-200 font-mono">
            <Database className="w-4 h-4 text-emerald-400" />
            <span>Per-Tensor Weight Residency Inspector ({inspectedTensors.length} Blocks)</span>
          </div>
          <span className="text-[11px] font-mono text-slate-400">
            {expandedTensorIndex ? 'Collapse ▲' : 'Inspect Tensors ▼'}
          </span>
        </button>

        {expandedTensorIndex && (
          <div className="p-3 overflow-x-auto">
            <table className="w-full text-left text-[11px] font-mono text-slate-300">
              <thead className="border-b border-slate-800 text-slate-400 uppercase text-[10px]">
                <tr>
                  <th className="pb-2">Tensor Name</th>
                  <th className="pb-2">Shape</th>
                  <th className="pb-2">Quant</th>
                  <th className="pb-2">Size</th>
                  <th className="pb-2">Assigned Node</th>
                  <th className="pb-2">Residency</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {inspectedTensors.map((t, idx) => (
                  <tr key={idx} className="hover:bg-slate-900/50 transition-colors">
                    <td className="py-2 text-slate-200 font-medium">{t.name}</td>
                    <td className="py-2 text-slate-400">{t.shape}</td>
                    <td className="py-2 text-amber-400">{t.quant}</td>
                    <td className="py-2 text-sky-400">{t.sizeMb} MB</td>
                    <td className="py-2 text-slate-300">{t.nodeLabel}</td>
                    <td className="py-2">
                      <span
                        className={`px-1.5 py-0.5 rounded-md text-[9px] font-bold ${
                          t.residency === 'Flash Cache'
                            ? 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30'
                            : t.residency === 'VRAM'
                              ? 'bg-purple-500/15 text-purple-400 border border-purple-500/30'
                              : 'bg-blue-500/15 text-blue-400 border border-blue-500/30'
                        }`}
                      >
                        {t.residency}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};
