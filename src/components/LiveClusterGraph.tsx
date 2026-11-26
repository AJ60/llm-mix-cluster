import React from 'react';
import {
  Cpu,
  Eye,
  Monitor,
  Network,
  Server,
  Smartphone,
  Thermometer,
  Wifi,
  Usb,
} from 'lucide-react';
import { GgufModelPreset, PlannedStarNode, StarClusterPlan } from '../types/starCluster';
import { isSameAndroidWorker } from '../domain/starClusterPlanner';

interface LiveClusterGraphProps {
  plan: StarClusterPlan;
  model: GgufModelPreset;
  selectedNodeId: string | null;
  onSelectNode: (nodeId: string) => void;
  onOpenPairHelp: () => void;
}

const NODE_COLORS = [
  { stroke: '#10B981', fill: 'bg-emerald-500', text: 'text-emerald-700 dark:text-emerald-400' },
  { stroke: '#0EA5E9', fill: 'bg-sky-500', text: 'text-sky-700 dark:text-sky-400' },
  { stroke: '#8B5CF6', fill: 'bg-violet-500', text: 'text-violet-700 dark:text-violet-400' },
  { stroke: '#F59E0B', fill: 'bg-amber-500', text: 'text-amber-700 dark:text-amber-400' },
];

function formatMem(mb: number): string {
  if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
  return `${Math.round(mb)} MB`;
}

function cleanNodeName(label: string): string {
  let s = label.replace(/\s*\((Master|Worker|Android)\)/gi, '').trim();
  if (/xiaomi/i.test(s) && /redmi/i.test(s)) {
    s = s.replace(/xiaomi\s*/i, '');
  }
  return s;
}

function transportIcon(linkType: PlannedStarNode['linkType']) {
  if (linkType === 'Local') return <Monitor className="w-3.5 h-3.5" />;
  if (linkType === 'USB-C Tunnel' || linkType === 'USB RNDIS') return <Usb className="w-3.5 h-3.5" />;
  if (linkType === 'Ethernet 2.5G') return <Network className="w-3.5 h-3.5" />;
  return <Wifi className="w-3.5 h-3.5" />;
}

function platformIcon(platform: PlannedStarNode['platform']) {
  if (platform === 'Android') return <Smartphone className="w-4 h-4" />;
  if (platform === 'Edge ARM') return <Cpu className="w-4 h-4" />;
  return <Server className="w-4 h-4" />;
}

export const LiveClusterGraph: React.FC<LiveClusterGraphProps> = ({
  plan,
  model,
  selectedNodeId,
  onSelectNode,
  onOpenPairHelp,
}) => {
  const master = plan.nodes.find((n) => n.role === 'Master') ?? plan.nodes[0];
  const rawWorkers = plan.nodes.filter(
    (n) => n.role === 'Worker' && (n.isConnected || (n.allocatedLayers ?? 0) > 0)
  );
  
  // Strict deduplication so duplicate aliases for the same physical device never display in the graph
  const workers: PlannedStarNode[] = [];
  for (const w of rawWorkers) {
    const isDup = workers.some((existing) =>
      isSameAndroidWorker(existing, w) || (existing.ip === w.ip && existing.rpcPort === w.rpcPort)
    );
    if (!isDup) workers.push(w);
  }

  const activeNodes = [master, ...workers].filter((n) => Boolean(n && n.allocatedLayers > 0 && !n.pruned));

  // SVG star-topology coordinates (scales dynamically for 1..N live workers with zero overlap)
  const svgW = 980;
  const workerCardW = 284;
  const workerCardH = 104;
  const workerGapY = 24;
  const svgH =
    workers.length === 0
      ? 196
      : Math.max(248, workers.length * (workerCardH + workerGapY) + 32);
  const masterPos = { x: workers.length === 0 ? svgW / 2 : 165, y: svgH / 2 };

  const workerPositions = workers.map((w, idx) => {
    const totalBlockH = workers.length * workerCardH + (workers.length - 1) * workerGapY;
    const startY = (svgH - totalBlockH) / 2 + workerCardH / 2;
    return {
      node: w,
      x: 796,
      y: startY + idx * (workerCardH + workerGapY),
      color: NODE_COLORS[(idx + 1) % NODE_COLORS.length],
    };
  });

  return (
    <section
      aria-label="Live Cluster Graph"
      data-testid="live-cluster-graph"
      className="rounded-xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-[#111827] overflow-hidden"
    >
      {/* Header */}
      <div className="px-5 py-4 border-b border-slate-200 dark:border-slate-800 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold text-slate-900 dark:text-slate-100">
            Live Cluster Graph
          </h2>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
            Verified online nodes only · {activeNodes.length} active compute{' '}
            {activeNodes.length === 1 ? 'node' : 'nodes'}
            {workers.length > Math.max(0, activeNodes.length - 1) && (
              <span className="text-slate-400">
                {' '}· {workers.length - Math.max(0, activeNodes.length - 1)} standby
              </span>
            )}
            {' '}· Split{' '}
            <span className="font-mono-num text-slate-700 dark:text-slate-200">
              {plan.tensorSplitLabel}
            </span>
            {plan.emaLearnedRuns && plan.emaLearnedRuns > 0
              ? ` · EMA learned from ${plan.emaLearnedRuns} runs`
              : ''}
          </p>
        </div>

        <div className="flex items-center gap-3 text-xs text-slate-600 dark:text-slate-300 font-mono-num">
          <span>
            {plan.useNoMmap ? '--no-mmap active' : 'mmap streaming'}
          </span>
          {plan.autoNkvo && <span>· -nkvo CPU KV</span>}
          {plan.masterMmprojPath && (
            <span className="inline-flex items-center gap-1 text-emerald-700 dark:text-emerald-400">
              · <Eye className="w-3.5 h-3.5" /> mmproj on Master
            </span>
          )}
        </div>
      </div>

      {/* Star Topology Graph Canvas */}
      <div className="p-4 sm:p-5 bg-slate-50/70 dark:bg-[#0D1424] border-b border-slate-200 dark:border-slate-800">
        <div className="w-full overflow-x-auto">
          <svg
            viewBox={`0 0 ${svgW} ${svgH}`}
            className="w-full min-w-[680px] h-auto"
            role="img"
            aria-label="Live cluster star topology showing Master and connected workers"
          >
            {/* Subtle engineering grid */}
            <defs>
              <pattern id="silica-grid" width="32" height="32" patternUnits="userSpaceOnUse">
                <path
                  d="M 32 0 L 0 0 0 32"
                  fill="none"
                  className="stroke-slate-200/70 dark:stroke-slate-800/70"
                  strokeWidth="0.75"
                />
              </pattern>
            </defs>
            <rect width={svgW} height={svgH} fill="url(#silica-grid)" rx="10" />

            {/* Edges from Master to each verified Worker */}
            {workerPositions.map(({ node, x, y, color }) => {
              const x1 = masterPos.x + 135;
              const x2 = x - workerCardW / 2;
              const midX = (x1 + x2) / 2;
              const midY = (masterPos.y + y) / 2;
              const linkBadge = `${node.linkType} · ${node.rttMs.toFixed(1)}ms · ${node.pktLossPct}% loss`;
              return (
                <g key={`edge-${node.nodeId}`}>
                  <line
                    x1={x1}
                    y1={masterPos.y}
                    x2={x2}
                    y2={y}
                    stroke={node.pruned || node.allocatedLayers === 0 ? '#94A3B8' : color.stroke}
                    strokeWidth={node.pruned ? 1.5 : 2.25}
                    strokeDasharray={node.pruned || node.allocatedLayers === 0 ? '5 5' : undefined}
                    opacity={node.pruned ? 0.55 : node.allocatedLayers === 0 ? 0.75 : 0.9}
                  />
                  <rect
                    x={midX - 116}
                    y={midY - 14}
                    width="232"
                    height="28"
                    rx="6"
                    className="fill-white dark:fill-[#111827] stroke-slate-200 dark:stroke-slate-700"
                    strokeWidth="1"
                  />
                  <text
                    x={midX}
                    y={midY + 4}
                    textAnchor="middle"
                    className="fill-slate-700 dark:fill-slate-200 text-[10.5px] font-mono-num"
                  >
                    {linkBadge}
                  </text>
                </g>
              );
            })}

            {/* Master Node Card inside SVG */}
            {master && (
              <g
                onClick={() => onSelectNode(master.nodeId)}
                className="cursor-pointer"
              >
                <rect
                  x={masterPos.x - 135}
                  y={masterPos.y - 62}
                  width="270"
                  height="124"
                  rx="10"
                  className={`fill-white dark:fill-[#111827] ${
                    selectedNodeId === master.nodeId
                      ? 'stroke-emerald-500'
                      : 'stroke-slate-300 dark:stroke-slate-700'
                  }`}
                  strokeWidth={selectedNodeId === master.nodeId ? 2 : 1.25}
                />
                <text
                  x={masterPos.x - 119}
                  y={masterPos.y - 38}
                  className="fill-emerald-700 dark:fill-emerald-400 text-[11px] font-semibold"
                >
                  MASTER · {master.ip}
                </text>
                <text
                  x={masterPos.x - 119}
                  y={masterPos.y - 18}
                  className="fill-slate-900 dark:fill-slate-100 text-[13px] font-semibold"
                >
                  {cleanNodeName(master.label)}
                </text>
                <text
                  x={masterPos.x - 119}
                  y={masterPos.y + 4}
                  className="fill-slate-600 dark:fill-slate-300 text-[11px] font-mono-num"
                >
                  {master.allocatedLayers > 0
                    ? `L${master.layerStart}–L${master.layerEnd} (${master.sharePct.toFixed(1)}%)`
                    : 'Coordinator'}{' '}
                  · {master.estimatedTokPerSec} tok/s
                </text>
                <text
                  x={masterPos.x - 119}
                  y={masterPos.y + 24}
                  className="fill-slate-500 dark:fill-slate-400 text-[11px] font-mono-num"
                >
                  Alloc {formatMem(master.allocatedMb)} / {formatMem(master.safeRamMb + master.safeVramMb)}
                </text>
                <text
                  x={masterPos.x - 119}
                  y={masterPos.y + 44}
                  className="fill-slate-500 dark:fill-slate-400 text-[11px] font-mono-num"
                >
                  {master.tempC.toFixed(0)}°C · {master.physicalCores} P-cores ·{' '}
                  {master.safeVramMb > 0 ? `${formatMem(master.safeVramMb)} VRAM` : 'CPU RAM'}
                </text>
              </g>
            )}

            {/* Verified Worker Nodes inside SVG */}
            {workerPositions.map(({ node, x, y, color }) => {
              const cardLeft = x - workerCardW / 2;
              const statusLine =
                node.allocatedLayers > 0
                  ? `L${node.layerStart}–L${node.layerEnd} (${node.sharePct.toFixed(1)}%) · ${node.estimatedTokPerSec} tok/s`
                  : node.pruneReason
                    ? node.pruneReason.length > 34
                      ? `${node.pruneReason.slice(0, 33)}…`
                      : node.pruneReason
                    : 'Standby (0 layers)';

              return (
                <g
                  key={node.nodeId}
                  onClick={() => onSelectNode(node.nodeId)}
                  className="cursor-pointer"
                >
                  <rect
                    x={cardLeft}
                    y={y - workerCardH / 2}
                    width={workerCardW}
                    height={workerCardH}
                    rx="10"
                    className={`fill-white dark:fill-[#111827] ${
                      selectedNodeId === node.nodeId
                        ? 'stroke-sky-500'
                        : 'stroke-slate-300 dark:stroke-slate-700'
                    }`}
                    strokeWidth={selectedNodeId === node.nodeId ? 2 : 1.25}
                  />
                  <text
                    x={cardLeft + 16}
                    y={y - 30}
                    fill={node.pruned ? '#94A3B8' : node.allocatedLayers === 0 ? '#38BDF8' : color.stroke}
                    className="text-[10.5px] font-semibold font-mono-num"
                  >
                    {node.pruned ? 'PRUNED' : node.allocatedLayers === 0 ? 'STANDBY WORKER' : 'ONLINE WORKER'} · {node.ip}:{node.rpcPort || 50055}
                  </text>
                  <text
                    x={cardLeft + 16}
                    y={y - 11}
                    className="fill-slate-900 dark:fill-slate-100 text-[13px] font-semibold"
                  >
                    {cleanNodeName(node.label)}
                  </text>
                  <text
                    x={cardLeft + 16}
                    y={y + 10}
                    className="fill-slate-600 dark:fill-slate-300 text-[10.5px] font-mono-num"
                  >
                    {statusLine}
                  </text>
                  <text
                    x={cardLeft + 16}
                    y={y + 30}
                    className="fill-slate-500 dark:fill-slate-400 text-[10.5px] font-mono-num"
                  >
                    Alloc {formatMem(node.allocatedMb)} / {formatMem(node.safeRamMb + node.safeVramMb)} ·{' '}
                    {node.tempC.toFixed(0)}°C · {node.physicalCores} cores
                  </text>
                </g>
              );
            })}
          </svg>
        </div>

        {/* Contiguous Layer Sharding Bar */}
        <div className="mt-4 space-y-2">
          <div className="flex items-center justify-between text-xs text-slate-600 dark:text-slate-400">
            <span className="font-medium text-slate-800 dark:text-slate-200">
              Layer Distribution ({model.layers} layers · {model.architecture})
            </span>
            <span className="font-mono-num">
              --tensor-split {plan.tensorSplitCsv || '100.0'}
            </span>
          </div>
          <div className="h-3.5 w-full rounded-md overflow-hidden bg-slate-200 dark:bg-slate-800 flex">
            {activeNodes.map((n, idx) => {
              const color = NODE_COLORS[idx % NODE_COLORS.length];
              return (
                <button
                  key={`bar-${n.nodeId}`}
                  type="button"
                  onClick={() => onSelectNode(n.nodeId)}
                  title={`${n.label}: L${n.layerStart}–L${n.layerEnd} (${n.sharePct.toFixed(1)}%)`}
                  style={{ width: `${Math.max(4, n.sharePct)}%` }}
                  className={`${color.fill} h-full border-r border-white/40 dark:border-slate-900/60 last:border-r-0 transition-opacity hover:opacity-90`}
                />
              );
            })}
          </div>
        </div>
      </div>

      {/* Real Active Node Roster */}
      <div className="p-4 sm:p-5">
        {workers.length === 0 && (
          <div className="mb-4 rounded-lg border border-dashed border-slate-300 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/40 p-3.5 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div className="text-xs text-slate-600 dark:text-slate-300">
              <span className="font-semibold text-slate-900 dark:text-slate-100">
                Single-Host Master Active.
              </span>{' '}
              Only verified online workers appear here. Connect an Android device over USB-C/Wi-Fi or run{' '}
              <code className="font-mono-num px-1 py-0.5 rounded bg-slate-200/70 dark:bg-slate-800">
                clusterd worker
              </code>{' '}
              on a second Windows/Linux/ARM64 PC to pool memory.
            </div>
            <button
              type="button"
              onClick={onOpenPairHelp}
              className="px-3 py-1.5 rounded-lg border border-slate-300 dark:border-slate-700 text-xs font-medium text-slate-700 dark:text-slate-200 hover:bg-white dark:hover:bg-slate-800 transition-colors shrink-0"
            >
              Pair Device
            </button>
          </div>
        )}

        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
          {[master, ...workers].map((node, idx) => {
            const color = NODE_COLORS[idx % NODE_COLORS.length];
            const totalCap = Math.max(1, node.safeRamMb + node.safeVramMb);
            const usedPct = Math.min(100, Math.round((node.allocatedMb / totalCap) * 100));
            const isSelected = selectedNodeId === node.nodeId;

            return (
              <button
                key={node.nodeId}
                type="button"
                onClick={() => onSelectNode(node.nodeId)}
                className={`text-left rounded-xl border p-4 transition-colors ${
                  isSelected
                    ? 'border-emerald-500/80 bg-emerald-500/[0.04] dark:bg-emerald-500/[0.06]'
                    : 'border-slate-200 dark:border-slate-800 bg-white dark:bg-[#0E1525] hover:border-slate-300 dark:hover:border-slate-700'
                }`}
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="flex items-center gap-1.5 text-xs font-medium text-slate-500 dark:text-slate-400">
                      <span className={color.text}>{platformIcon(node.platform)}</span>
                      <span>{node.role}</span>
                      <span>·</span>
                      <span className="font-mono-num">{node.ip}</span>
                    </div>
                    <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100 truncate mt-1">
                      {node.label}
                    </h3>
                  </div>
                  <span
                    className={`text-[11px] font-medium ${
                      node.pruned
                        ? 'text-amber-700 dark:text-amber-400'
                        : node.allocatedLayers === 0 && node.role === 'Worker'
                          ? 'text-sky-700 dark:text-sky-400'
                          : 'text-emerald-700 dark:text-emerald-400'
                    }`}
                  >
                    {node.pruned
                      ? 'Pruned'
                      : node.allocatedLayers === 0 && node.role === 'Worker'
                        ? 'Standby'
                        : 'Online'}
                  </span>
                </div>

                {/* Transport & Link telemetry */}
                <div className="mt-3 flex items-center justify-between text-xs text-slate-600 dark:text-slate-400">
                  <span className="inline-flex items-center gap-1.5">
                    {transportIcon(node.linkType)}
                    {node.linkType}
                  </span>
                  <span className="font-mono-num">
                    p95 {node.rttMs.toFixed(1)} ms · {node.pktLossPct}% loss
                  </span>
                </div>

                {/* Hardware & Memory allocation */}
                <div className="mt-3 space-y-1.5">
                  <div className="flex items-center justify-between text-xs">
                    <span className="text-slate-500 dark:text-slate-400">Allocated / Safe Cap</span>
                    <span className="font-mono-num font-medium text-slate-900 dark:text-slate-100">
                      {formatMem(node.allocatedMb)} / {formatMem(totalCap)}
                    </span>
                  </div>
                  <div className="h-1.5 w-full rounded-full bg-slate-200 dark:bg-slate-800 overflow-hidden">
                    <div
                      className={`h-full rounded-full ${color.fill}`}
                      style={{ width: `${usedPct}%` }}
                    />
                  </div>
                </div>

                {/* Layer & Speed footer */}
                <div className="mt-3 pt-2.5 border-t border-slate-100 dark:border-slate-800/80 flex items-center justify-between text-xs font-mono-num text-slate-600 dark:text-slate-300">
                  <span>
                    {node.allocatedLayers > 0
                      ? `L${node.layerStart}–L${node.layerEnd} · ${node.sharePct.toFixed(1)}%`
                      : node.statusNote || 'Standby (0 layers)'}
                  </span>
                  <span className="inline-flex items-center gap-2">
                    <span className="inline-flex items-center gap-0.5">
                      <Thermometer className="w-3 h-3 text-slate-400" />
                      {node.tempC.toFixed(0)}°C
                    </span>
                    <span>{node.estimatedTokPerSec} tok/s</span>
                  </span>
                </div>
              </button>
            );
          })}
        </div>
      </div>
    </section>
  );
};
