import { useState } from "react";

export default function ClusterTopology({ nodes, onSnapshot }) {
  const [snapping, setSnapping] = useState({});

  const handleSnapshot = async (nodeId) => {
    setSnapping((prev) => ({ ...prev, [nodeId]: true }));
    try {
      await onSnapshot(nodeId);
    } finally {
      setTimeout(() => setSnapping((prev) => ({ ...prev, [nodeId]: false })), 1500);
    }
  };

  const getUsagePercent = (node) => {
    if (!node.maxCapacity || node.maxCapacity === 0) return 0;
    return Math.min(100, Math.round((node.totalKeys / node.maxCapacity) * 100));
  };

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {nodes.map((node) => {
        const alive = node.status === "alive";
        const usage = getUsagePercent(node);

        return (
          <div
            key={node.id}
            className={`glass-card-hover relative overflow-hidden p-6 ${
              alive ? "" : "opacity-60"
            }`}
          >
            {/* Status indicator bar at top */}
            <div
              className={`absolute left-0 right-0 top-0 h-0.5 ${
                alive
                  ? "bg-gradient-to-r from-emerald-400 via-emerald-500 to-emerald-400"
                  : "bg-gradient-to-r from-red-400 via-red-500 to-red-400"
              }`}
            />

            <div className="flex items-start justify-between">
              <div className="flex items-center gap-3">
                <span className={alive ? "status-dot-alive" : "status-dot-dead"} />
                <div>
                  <p className="text-sm font-semibold text-white">{node.id}</p>
                  <p className="font-mono text-xs text-gray-500">
                    {node.host}:{node.port}
                  </p>
                </div>
              </div>
              <span className={alive ? "badge-success" : "badge-danger"}>
                {alive ? "ALIVE" : "DEAD"}
              </span>
            </div>

            {/* Stats grid */}
            <div className="mt-5 grid grid-cols-2 gap-4">
              <div>
                <p className="text-[10px] font-medium uppercase tracking-wider text-gray-600">Keys</p>
                <p className="mt-0.5 text-lg font-bold text-white">
                  {(node.totalKeys || 0).toLocaleString()}
                </p>
              </div>
              <div>
                <p className="text-[10px] font-medium uppercase tracking-wider text-gray-600">Evictions</p>
                <p className="mt-0.5 text-lg font-bold text-white">
                  {(node.evictionCount || 0).toLocaleString()}
                </p>
              </div>
              <div>
                <p className="text-[10px] font-medium uppercase tracking-wider text-gray-600">Memory</p>
                <p className="mt-0.5 text-lg font-bold text-white">
                  {formatBytes(node.memoryUsedBytes || 0)}
                </p>
              </div>
              <div>
                <p className="text-[10px] font-medium uppercase tracking-wider text-gray-600">Repl. Lag</p>
                <p className="mt-0.5 text-lg font-bold text-white">
                  {node.replicationLagMs || 0}
                  <span className="ml-0.5 text-xs font-normal text-gray-500">ms</span>
                </p>
              </div>
            </div>

            {/* Memory progress bar */}
            <div className="mt-4">
              <div className="mb-1 flex items-center justify-between">
                <span className="text-[10px] font-medium uppercase tracking-wider text-gray-600">
                  Capacity
                </span>
                <span className="text-xs font-medium text-gray-400">{usage}%</span>
              </div>
              <div className="progress-bar-track">
                <div
                  className="progress-bar-fill"
                  style={{
                    width: `${usage}%`,
                    background:
                      usage > 90
                        ? "linear-gradient(90deg, #ef4444, #f87171)"
                        : usage > 70
                          ? "linear-gradient(90deg, #f59e0b, #fbbf24)"
                          : "linear-gradient(90deg, #6366f1, #818cf8, #a5b4fc)",
                  }}
                />
              </div>
            </div>

            {/* Snapshot button */}
            <button
              id={`snapshot-${node.id}`}
              onClick={() => handleSnapshot(node.id)}
              disabled={!alive || snapping[node.id]}
              className="btn-primary mt-4 w-full justify-center text-xs disabled:cursor-not-allowed disabled:opacity-40"
            >
              {snapping[node.id] ? (
                <>
                  <svg className="h-3.5 w-3.5 animate-spin" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  Snapshotting...
                </>
              ) : (
                <>
                  <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z" />
                    <path strokeLinecap="round" strokeLinejoin="round" d="M15 13a3 3 0 11-6 0 3 3 0 016 0z" />
                  </svg>
                  Snapshot
                </>
              )}
            </button>
          </div>
        );
      })}
    </div>
  );
}

function formatBytes(bytes) {
  if (bytes === 0) return "0 B";
  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + " " + sizes[i];
}
