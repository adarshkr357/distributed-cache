import { useState } from "react";

export default function NodeRegistration({ onRegister }) {
  const [form, setForm] = useState({ host: "", port: "", slaveHost: "", slavePort: "" });
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(false);

  const handleChange = (e) => {
    setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!form.host || !form.port) {
      setStatus({ type: "error", message: "Host and port are required" });
      return;
    }

    setLoading(true);
    setStatus(null);

    try {
      await onRegister({
        host: form.host,
        port: parseInt(form.port),
        slaveHost: form.slaveHost || undefined,
        slavePort: form.slavePort ? parseInt(form.slavePort) : undefined,
      });
      setStatus({ type: "success", message: "Node registered successfully" });
      setForm({ host: "", port: "", slaveHost: "", slavePort: "" });
    } catch (err) {
      setStatus({ type: "error", message: err.message });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="glass-card p-6">
      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor="reg-host" className="mb-1.5 block text-[10px] font-semibold uppercase tracking-wider text-gray-500">
              Host
            </label>
            <input
              id="reg-host"
              name="host"
              value={form.host}
              onChange={handleChange}
              placeholder="cache-node-4"
              className="w-full rounded-xl border border-white/[0.06] bg-white/[0.03] px-3 py-2.5 font-mono text-sm text-white placeholder-gray-600 outline-none transition-colors focus:border-brand-500/50 focus:ring-1 focus:ring-brand-500/20"
            />
          </div>
          <div>
            <label htmlFor="reg-port" className="mb-1.5 block text-[10px] font-semibold uppercase tracking-wider text-gray-500">
              Port
            </label>
            <input
              id="reg-port"
              name="port"
              value={form.port}
              onChange={handleChange}
              placeholder="6004"
              type="number"
              className="w-full rounded-xl border border-white/[0.06] bg-white/[0.03] px-3 py-2.5 font-mono text-sm text-white placeholder-gray-600 outline-none transition-colors focus:border-brand-500/50 focus:ring-1 focus:ring-brand-500/20"
            />
          </div>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label htmlFor="reg-slave-host" className="mb-1.5 block text-[10px] font-semibold uppercase tracking-wider text-gray-500">
              Slave Host
            </label>
            <input
              id="reg-slave-host"
              name="slaveHost"
              value={form.slaveHost}
              onChange={handleChange}
              placeholder="cache-node-1"
              className="w-full rounded-xl border border-white/[0.06] bg-white/[0.03] px-3 py-2.5 font-mono text-sm text-white placeholder-gray-600 outline-none transition-colors focus:border-brand-500/50 focus:ring-1 focus:ring-brand-500/20"
            />
          </div>
          <div>
            <label htmlFor="reg-slave-port" className="mb-1.5 block text-[10px] font-semibold uppercase tracking-wider text-gray-500">
              Slave Port
            </label>
            <input
              id="reg-slave-port"
              name="slavePort"
              value={form.slavePort}
              onChange={handleChange}
              placeholder="6001"
              type="number"
              className="w-full rounded-xl border border-white/[0.06] bg-white/[0.03] px-3 py-2.5 font-mono text-sm text-white placeholder-gray-600 outline-none transition-colors focus:border-brand-500/50 focus:ring-1 focus:ring-brand-500/20"
            />
          </div>
        </div>

        <button
          id="register-node-btn"
          type="submit"
          disabled={loading}
          className="btn-primary w-full justify-center disabled:opacity-50"
        >
          {loading ? (
            <>
              <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
              </svg>
              Registering...
            </>
          ) : (
            <>
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
              </svg>
              Register Node
            </>
          )}
        </button>

        {status && (
          <div
            className={`rounded-xl p-3 text-xs font-medium ${
              status.type === "success"
                ? "bg-emerald-500/10 text-emerald-400"
                : "bg-red-500/10 text-red-400"
            }`}
          >
            {status.message}
          </div>
        )}
      </form>
    </div>
  );
}
