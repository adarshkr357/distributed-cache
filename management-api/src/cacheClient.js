/**
 * TCP client helper for communicating with Java cache nodes.
 * Sends a command, reads the response, and closes the connection.
 */

const net = require("net");

/**
 * Send a command to a cache node via TCP.
 * @param {string} host - Cache node host
 * @param {number} port - Cache node port
 * @param {string} command - Command string (e.g., "PING", "STATS")
 * @param {number} [timeoutMs=5000] - Connection timeout
 * @returns {Promise<string>} Response from the node
 */
function sendCommand(host, port, command, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const socket = new net.Socket();
    let response = "";
    let resolved = false;

    socket.setTimeout(timeoutMs);

    socket.connect(port, host, () => {
      socket.write(command + "\n");
    });

    socket.on("data", (data) => {
      response += data.toString();
      if (response.includes("\n")) {
        if (!resolved) {
          resolved = true;
          socket.write("QUIT\n");
          socket.end();
          resolve(response.trim().split("\n")[0]);
        }
      }
    });

    socket.on("timeout", () => {
      if (!resolved) {
        resolved = true;
        socket.destroy();
        reject(new Error(`Connection to ${host}:${port} timed out`));
      }
    });

    socket.on("error", (err) => {
      if (!resolved) {
        resolved = true;
        socket.destroy();
        reject(new Error(`Connection to ${host}:${port} failed: ${err.message}`));
      }
    });

    socket.on("close", () => {
      if (!resolved) {
        resolved = true;
        resolve(response.trim());
      }
    });
  });
}

/**
 * Ping a cache node.
 * @returns {Promise<boolean>}
 */
async function pingNode(host, port) {
  try {
    const response = await sendCommand(host, port, "PING", 3000);
    return response === "+PONG";
  } catch {
    return false;
  }
}

/**
 * Get stats from a cache node.
 * @returns {Promise<object|null>}
 */
async function getNodeStats(host, port) {
  try {
    const response = await sendCommand(host, port, "STATS", 5000);
    return JSON.parse(response);
  } catch {
    return null;
  }
}

module.exports = { sendCommand, pingNode, getNodeStats };
