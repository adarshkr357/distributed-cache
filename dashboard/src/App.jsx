import { useState, useEffect, useCallback, useRef } from "react";
import axios from "axios";
import ClusterTopology from "./components/ClusterTopology.jsx";
import MemoryChart from "./components/MemoryChart.jsx";
import ReplicationTable from "./components/ReplicationTable.jsx";
import WALViewer from "./components/WALViewer.jsx";
import NodeRegistration from "./components/NodeRegistration.jsx";

const API_BASE = import.meta.env.VITE_API_URL || "";
const API_KEY = import.meta.env.VITE_API_KEY || "dcache-secret-key-change-me";

const api = axios.create({
  baseURL: API_BASE,
  headers: { "X-API-Key": API_KEY },
  timeout: 10000,
});

export default function App() {
  const [nodes, setNodes] = useState([]);
  const [walEntries, setWalEntries] = useState([]);
  const [memoryHistory, setMemoryHistory] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [lastUpdated, setLastUpdated] = useState(null);
  const historyRef = useRef([]);

  const fetchData = useCallback(async () => {
    try {
      const [nodesRes, walRes] = await Promise.all([
        api.get("/api/cluster/nodes"),
        api.get("/api/cluster/wal"),
      ]);

      setNodes(nodesRes.data.nodes || []);
      setWalEntries(walRes.data.entries || []);
      setError(null);

      // Build memory history data point
      const timestamp = new Date().toLocaleTimeString();
      const point = { time: timestamp };
      for (const node of nodesRes.data.nodes || []) {
        point[node.id] = node.memoryUsedBytes || 0;
      }
      historyRef.current = [...historyRef.current.slice(-29), point];
      setMemoryHistory([...historyRef.current]);
      setLastUpdated(new Date());
    } catch (err) {
      setError(err.response?.data?.error || err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 3000);
    return () => clearInterval(interval);
  }, [fetchData]);

  const handleSnapshot = async (nodeId) => {
    try {
      await api.post("/api/cluster/snapshot", nodeId ? { nodeId } : {});
    } catch (err) {
      alert("Snapshot failed: " + (err.response?.data?.error || err.message));
    }
  };

  const handleRegisterNode = async (nodeData) => {
    try {
      await api.post("/api/cluster/nodes", nodeData);
      await fetchData();
    } catch (err) {
      throw new Error(err.response?.data?.error || err.message);
    }
  };

  const aliveCount = nodes.filter((n) => n.status === "alive").length;
  const totalKeys = nodes.reduce((sum, n) => sum + (n.totalKeys || 0), 0);
  const totalMemory = nodes.reduce((sum, n) => sum + (n.memoryUsedBytes || 0), 0);

  return (
    <div className="min-h-screen bg-gray-950">
      {/* Header */}
      <header className="sticky top-0 z-50 border-b border-white/[0.06] bg-gray-950/80 backdrop-blur-xl">
        <div className="mx-auto flex max-w-[1600px] items-center justify-between px-6 py-4">
          <div className="flex items-center gap-4">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-brand-500 to-brand-700">
              <svg className="h-5 w-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4" />
              </svg>
            </div>
            <div>
              <h1 className="text-lg font-bold tracking-tight text-white">Distributed Cache</h1>
              <p className="text-xs text-gray-500">Cluster Monitoring Dashboard</p>
            </div>
          </div>
          <div className="flex items-center gap-6">
            {lastUpdated && (
              <span className="text-xs text-gray-500">
                Updated {lastUpdated.toLocaleTimeString()}
              </span>
            )}
            <div className="flex items-center gap-2">
              <span className={`status-dot ${aliveCount > 0 ? "status-dot-alive" : "status-dot-dead"}`} />
              <span className="text-sm font-medium text-gray-300">
                {aliveCount}/{nodes.length} nodes
              </span>
            </div>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-[1600px] px-6 py-8 space-y-8">
        {/* Error Banner */}
        {error && (
          <div className="glass-card border-red-500/20 bg-red-500/5 p-4">
            <p className="text-sm text-red-400">
              <span className="font-semibold">Connection Error:</span> {error}
            </p>
          </div>
        )}

        {/* Quick Stats */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {[
            { label: "Active Nodes", value: `${aliveCount} / ${nodes.length}`, icon: "🟢", color: "from-emerald-500/10 to-emerald-600/5" },
            { label: "Total Keys", value: totalKeys.toLocaleString(), icon: "🔑", color: "from-brand-500/10 to-brand-600/5" },
            { label: "Memory Used", value: formatBytes(totalMemory), icon: "💾", color: "from-amber-500/10 to-amber-600/5" },
            { label: "WAL Entries", value: walEntries.length.toString(), icon: "📝", color: "from-purple-500/10 to-purple-600/5" },
          ].map((stat) => (
            <div key={stat.label} className={`glass-card bg-gradient-to-br ${stat.color} p-5`}>
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-xs font-medium uppercase tracking-wider text-gray-500">{stat.label}</p>
                  <p className="mt-1 text-2xl font-bold text-white">{stat.value}</p>
                </div>
                <span className="text-2xl">{stat.icon}</span>
              </div>
            </div>
          ))}
        </div>

        {/* Cluster Topology */}
        <section>
          <h2 className="mb-4 text-base font-semibold text-gray-300">Cluster Topology</h2>
          <ClusterTopology nodes={nodes} onSnapshot={handleSnapshot} />
        </section>

        {/* Charts Row */}
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <section>
            <h2 className="mb-4 text-base font-semibold text-gray-300">Memory Usage Over Time</h2>
            <MemoryChart data={memoryHistory} nodes={nodes} />
          </section>
          <section>
            <h2 className="mb-4 text-base font-semibold text-gray-300">Replication Status</h2>
            <ReplicationTable nodes={nodes} />
          </section>
        </div>

        {/* WAL + Registration */}
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
          <div className="lg:col-span-2">
            <h2 className="mb-4 text-base font-semibold text-gray-300">Write-Ahead Log</h2>
            <WALViewer entries={walEntries} />
          </div>
          <div>
            <h2 className="mb-4 text-base font-semibold text-gray-300">Register Node</h2>
            <NodeRegistration onRegister={handleRegisterNode} />
          </div>
        </div>
      </main>
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
