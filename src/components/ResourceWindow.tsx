import React from 'react';
import {
  Cpu,
  HardDrive,
  MemoryStick,
  Monitor,
  Wifi,
  type LucideIcon,
} from 'lucide-react';
import { LiveHardwareProbe, StarClusterPlan } from '../types/starCluster';

interface ResourceWindowProps {
  hardware?: LiveHardwareProbe;
  plan: StarClusterPlan;
}

interface MetricProps {
  icon: LucideIcon;
  label: string;
  value: string;
  detail: string;
  percent: number;
  color: string;
}

const formatMb = (mb: number): string => {
  if (!Number.isFinite(mb) || mb <= 0) return '—';
  if (mb >= 1024 * 1024) return `${(mb / (1024 * 1024)).toFixed(1)} TB`;
  if (mb >= 1024) return `${(mb / 1024).toFixed(0)} GB`;
  return `${mb.toFixed(0)} MB`;
};

const clampPct = (value: number): number => Math.max(0, Math.min(100, value || 0));

const Metric: React.FC<MetricProps> = ({ icon: Icon, label, value, detail, percent, color }) => (
  <div className="p-2.5 rounded-lg border border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-[#0D1424] space-y-1.5">
    <div className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
      <Icon className="w-3.5 h-3.5" style={{ color }} />
      {label}
    </div>
    <div className="font-mono-num text-sm font-bold text-slate-900 dark:text-slate-100">{value}</div>
    <div className="h-1.5 w-full rounded-full bg-slate-200 dark:bg-slate-800 overflow-hidden">
      <div
        className="h-full rounded-full transition-all duration-500"
        style={{ width: `${clampPct(percent)}%`, backgroundColor: color }}
      />
    </div>
    <div className="text-[10px] text-slate-500 dark:text-slate-400 truncate">{detail}</div>
  </div>
);

export const ResourceWindow: React.FC<ResourceWindowProps> = ({ hardware, plan }) => {
  const totalRamMb = hardware?.totalRamMb ?? 0;
  const freeRamMb = hardware?.availableRamMb ?? 0;
  const ramUsedPct = totalRamMb > 0 ? ((totalRamMb - freeRamMb) / totalRamMb) * 100 : 0;

  const storageTotalMb = hardware?.storageTotalMb ?? hardware?.host?.storage_total_mb ?? 0;
  const storageFreeMb = hardware?.storageFreeMb ?? hardware?.host?.storage_free_mb ?? 0;
  const storageUsedPct = storageTotalMb > 0
    ? ((storageTotalMb - storageFreeMb) / storageTotalMb) * 100
    : 0;

  const bandwidths = plan.nodes
    .filter((node) => !node.pruned && node.allocatedLayers > 0 && (node.bandwidthMbps ?? 0) > 0)
    .map((node) => node.bandwidthMbps as number);
  const peakBandwidthMbps = bandwidths.length > 0 ? Math.max(...bandwidths) : 0;
  const bandwidthPct = Math.min(100, (peakBandwidthMbps / 2500) * 100);

  const nodes = plan.nodes.filter((node) => !node.pruned && node.allocatedLayers > 0);

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
        <Metric
          icon={Cpu}
          label="CPU"
          value={`${(hardware?.cpuUsagePct ?? 0).toFixed(0)}%`}
          detail={`${hardware?.cores ?? 0} cores · ${hardware?.bigCores ?? 0} big`}
          percent={hardware?.cpuUsagePct ?? 0}
          color="#10b981"
        />
        <Metric
          icon={Monitor}
          label="GPU"
          value={`${(hardware?.gpuUsagePct ?? 0).toFixed(0)}%`}
          detail={hardware?.gpuName || 'No discrete GPU'}
          percent={hardware?.gpuUsagePct ?? 0}
          color="#0ea5e9"
        />
        <Metric
          icon={MemoryStick}
          label="RAM"
          value={formatMb(freeRamMb)}
          detail={`${ramUsedPct.toFixed(0)}% used of ${formatMb(totalRamMb)}`}
          percent={ramUsedPct}
          color="#8b5cf6"
        />
        <Metric
          icon={HardDrive}
          label="Storage"
          value={formatMb(storageFreeMb)}
          detail={`${storageUsedPct.toFixed(0)}% used of ${formatMb(storageTotalMb)}`}
          percent={storageUsedPct}
          color="#f59e0b"
        />
        <Metric
          icon={Wifi}
          label="Bandwidth"
          value={peakBandwidthMbps > 0 ? `${peakBandwidthMbps.toFixed(0)} Mb/s` : '—'}
          detail={`${nodes.length} active ${nodes.length === 1 ? 'node' : 'nodes'}`}
          percent={bandwidthPct}
          color="#14b8a6"
        />
      </div>

      <div className="space-y-1.5">
        {nodes.map((node) => {
          const usedPct = node.totalRamMb > 0
            ? ((node.totalRamMb - node.availableRamMb) / node.totalRamMb) * 100
            : 0;
          return (
            <div
              key={node.nodeId}
              className="flex items-center justify-between gap-3 p-2 rounded-lg border border-slate-100 dark:border-slate-800 bg-white dark:bg-[#111827]"
            >
              <div className="min-w-0">
                <p className="text-[11px] font-semibold text-slate-900 dark:text-slate-100 truncate">
                  {node.label}
                </p>
                <p className="text-[10px] text-slate-500 dark:text-slate-400 truncate">
                  L{node.layerStart}–L{node.layerEnd} · {formatMb(node.allocatedMb)} · {(node.bandwidthMbps ?? 0).toFixed(0)} Mb/s
                </p>
              </div>
              <div className="w-20 shrink-0">
                <div className="h-1.5 w-full rounded-full bg-slate-200 dark:bg-slate-800 overflow-hidden">
                  <div
                    className="h-full rounded-full bg-sky-500"
                    style={{ width: `${clampPct(usedPct)}%` }}
                  />
                </div>
                <p className="text-[10px] text-right text-slate-500 dark:text-slate-400 font-mono-num">
                  {usedPct.toFixed(0)}% RAM
                </p>
              </div>
            </div>
          );
        })}
      </div>

      <div className="text-[10px] text-slate-500 dark:text-slate-400 font-mono-num">
        Effective context {(plan.effectiveContext ?? 0).toLocaleString()} tokens · handoff {(plan.estimatedHandoffMs ?? 0).toFixed(1)} ms · KV {formatMb(plan.kvCacheTotalMb)}
      </div>
    </div>
  );
};
