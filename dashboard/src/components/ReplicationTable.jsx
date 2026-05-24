export default function ReplicationTable({ nodes }) {
  return (
    <div className="glass-card overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="border-b border-white/[0.06]">
              <th className="px-5 py-3 text-left text-[10px] font-semibold uppercase tracking-wider text-gray-500">
                Master Node
              </th>
              <th className="px-5 py-3 text-left text-[10px] font-semibold uppercase tracking-wider text-gray-500">
                Slave
              </th>
              <th className="px-5 py-3 text-left text-[10px] font-semibold uppercase tracking-wider text-gray-500">
                Status
              </th>
              <th className="px-5 py-3 text-right text-[10px] font-semibold uppercase tracking-wider text-gray-500">
                Lag
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-white/[0.04]">
            {nodes.map((node) => {
              const lag = node.replicationLagMs || 0;
              const lagStatus = lag < 0 ? "dead" : lag < 50 ? "good" : lag < 200 ? "warn" : "bad";

              return (
                <tr
                  key={node.id}
                  className="transition-colors hover:bg-white/[0.02]"
                >
                  <td className="px-5 py-3.5">
                    <div className="flex items-center gap-2">
                      <span className={node.status === "alive" ? "status-dot-alive" : "status-dot-dead"} />
                      <div>
                        <p className="text-sm font-medium text-white">{node.id}</p>
                        <p className="font-mono text-[10px] text-gray-600">
                          {node.host}:{node.port}
                        </p>
                      </div>
                    </div>
                  </td>
                  <td className="px-5 py-3.5">
                    <p className="font-mono text-xs text-gray-400">
                      {node.slaveHost
                        ? `${node.slaveHost}:${node.slavePort}`
                        : "—"}
                    </p>
                  </td>
                  <td className="px-5 py-3.5">
                    {lagStatus === "dead" ? (
                      <span className="badge-danger">UNREACHABLE</span>
                    ) : lagStatus === "good" ? (
                      <span className="badge-success">SYNCED</span>
                    ) : lagStatus === "warn" ? (
                      <span className="badge bg-amber-500/10 text-amber-400 ring-1 ring-amber-500/20">
                        LAGGING
                      </span>
                    ) : (
                      <span className="badge-danger">HIGH LAG</span>
                    )}
                  </td>
                  <td className="px-5 py-3.5 text-right">
                    <span
                      className={`font-mono text-sm font-semibold ${
                        lagStatus === "good"
                          ? "text-emerald-400"
                          : lagStatus === "warn"
                            ? "text-amber-400"
                            : "text-red-400"
                      }`}
                    >
                      {lag < 0 ? "—" : `${lag}ms`}
                    </span>
                  </td>
                </tr>
              );
            })}
            {nodes.length === 0 && (
              <tr>
                <td colSpan={4} className="px-5 py-8 text-center text-sm text-gray-600">
                  No nodes registered
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
