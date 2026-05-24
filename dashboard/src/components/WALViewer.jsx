import { useState } from "react";

const PAGE_SIZE = 15;

export default function WALViewer({ entries }) {
  const [page, setPage] = useState(0);
  const totalPages = Math.max(1, Math.ceil(entries.length / PAGE_SIZE));
  const pageEntries = entries.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);

  return (
    <div className="glass-card overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="border-b border-white/[0.06]">
              {["ID", "Operation", "Key", "Value", "TTL", "Node", "Timestamp", "Applied"].map(
                (h) => (
                  <th
                    key={h}
                    className="px-4 py-3 text-left text-[10px] font-semibold uppercase tracking-wider text-gray-500"
                  >
                    {h}
                  </th>
                )
              )}
            </tr>
          </thead>
          <tbody className="divide-y divide-white/[0.04]">
            {pageEntries.map((entry) => (
              <tr
                key={entry.id}
                className="transition-colors hover:bg-white/[0.02]"
              >
                <td className="px-4 py-2.5 font-mono text-xs text-gray-500">
                  {entry.id}
                </td>
                <td className="px-4 py-2.5">
                  <span
                    className={
                      entry.operation === "SET"
                        ? "badge-info"
                        : "badge-danger"
                    }
                  >
                    {entry.operation}
                  </span>
                </td>
                <td className="px-4 py-2.5 font-mono text-xs text-gray-300">
                  {entry.cache_key}
                </td>
                <td className="max-w-[200px] truncate px-4 py-2.5 font-mono text-xs text-gray-500">
                  {entry.cache_value || "—"}
                </td>
                <td className="px-4 py-2.5 font-mono text-xs text-gray-500">
                  {entry.ttl || "∞"}
                </td>
                <td className="px-4 py-2.5 text-xs text-gray-400">
                  {entry.node_id}
                </td>
                <td className="px-4 py-2.5 text-xs text-gray-500">
                  {entry.timestamp
                    ? new Date(entry.timestamp).toLocaleString()
                    : "—"}
                </td>
                <td className="px-4 py-2.5">
                  {entry.applied ? (
                    <svg className="h-4 w-4 text-emerald-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                    </svg>
                  ) : (
                    <svg className="h-4 w-4 text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                    </svg>
                  )}
                </td>
              </tr>
            ))}
            {pageEntries.length === 0 && (
              <tr>
                <td colSpan={8} className="px-4 py-8 text-center text-sm text-gray-600">
                  No WAL entries found
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between border-t border-white/[0.06] px-4 py-3">
          <span className="text-xs text-gray-500">
            Page {page + 1} of {totalPages} · {entries.length} entries
          </span>
          <div className="flex gap-1.5">
            <button
              id="wal-prev-page"
              onClick={() => setPage(Math.max(0, page - 1))}
              disabled={page === 0}
              className="rounded-lg bg-white/[0.05] px-3 py-1.5 text-xs font-medium text-gray-400 transition-colors hover:bg-white/[0.1] disabled:cursor-not-allowed disabled:opacity-30"
            >
              ← Prev
            </button>
            <button
              id="wal-next-page"
              onClick={() => setPage(Math.min(totalPages - 1, page + 1))}
              disabled={page >= totalPages - 1}
              className="rounded-lg bg-white/[0.05] px-3 py-1.5 text-xs font-medium text-gray-400 transition-colors hover:bg-white/[0.1] disabled:cursor-not-allowed disabled:opacity-30"
            >
              Next →
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
