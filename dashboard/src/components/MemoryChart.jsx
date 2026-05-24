import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from "recharts";

const COLORS = ["#6366f1", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6", "#06b6d4"];

export default function MemoryChart({ data, nodes }) {
  if (data.length === 0) {
    return (
      <div className="glass-card flex h-[300px] items-center justify-center">
        <p className="text-sm text-gray-500">Collecting data...</p>
      </div>
    );
  }

  const nodeIds = nodes.map((n) => n.id);

  return (
    <div className="glass-card p-6">
      <ResponsiveContainer width="100%" height={300}>
        <LineChart data={data}>
          <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.04)" />
          <XAxis
            dataKey="time"
            tick={{ fill: "#6b7280", fontSize: 10 }}
            axisLine={{ stroke: "rgba(255,255,255,0.06)" }}
            tickLine={false}
          />
          <YAxis
            tick={{ fill: "#6b7280", fontSize: 10 }}
            axisLine={{ stroke: "rgba(255,255,255,0.06)" }}
            tickLine={false}
            tickFormatter={(v) => {
              if (v >= 1048576) return `${(v / 1048576).toFixed(1)}MB`;
              if (v >= 1024) return `${(v / 1024).toFixed(1)}KB`;
              return `${v}B`;
            }}
          />
          <Tooltip
            contentStyle={{
              backgroundColor: "rgba(15, 15, 20, 0.95)",
              border: "1px solid rgba(255,255,255,0.1)",
              borderRadius: "12px",
              fontSize: "12px",
              color: "#e5e7eb",
              backdropFilter: "blur(12px)",
            }}
            formatter={(value) => {
              if (value >= 1048576) return [`${(value / 1048576).toFixed(2)} MB`];
              if (value >= 1024) return [`${(value / 1024).toFixed(2)} KB`];
              return [`${value} B`];
            }}
          />
          <Legend
            wrapperStyle={{ fontSize: "11px", color: "#9ca3af" }}
          />
          {nodeIds.map((id, idx) => (
            <Line
              key={id}
              type="monotone"
              dataKey={id}
              name={id}
              stroke={COLORS[idx % COLORS.length]}
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4, strokeWidth: 0 }}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
