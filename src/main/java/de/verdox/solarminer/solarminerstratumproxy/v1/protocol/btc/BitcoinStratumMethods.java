package de.verdox.solarminer.solarminerstratumproxy.v1.protocol.btc;

public class BitcoinStratumMethods {

    /**
     * Usage: Initial handshake from miner to pool. Subscribes to the job stream.
     * Response: Pool answers with extranonce1 (hex string for miner) and extranonce2_size (length of bytes the miner is allowed to count up).
     */
    public static final String MINING_SUBSCRIBE = "mining.subscribe";

    /**
     * Usage: Miner logs in to the pool. Sends worker name (e.g., wallet.worker) and password.
     * Response: Boolean (true) if authorization is successful.
     */
    public static final String MINING_AUTHORIZE = "mining.authorize";

    /**
     * Usage: Miner submits a found share (valid hash) to the pool.
     * Parameters: Worker name, job ID, extranonce2, ntime, and nonce.
     * Response: Boolean (true) if the share is accepted, or an error array if rejected.
     */
    public static final String MINING_SUBMIT = "mining.submit";

    /**
     * Usage: (BIP310 / Stratum V1 Extensions) Used by modern ASICs to negotiate advanced features with the pool (e.g., Version-Rolling for ASICBoost).
     * Response: Map of supported features and their states.
     */
    public static final String MINING_CONFIGURE = "mining.configure";

    /**
     * Usage: Miner tells the pool it supports the mining.set_extranonce method.
     * The pool should send a new extranonce instead of disconnecting when extranonce2 runs out.
     * Response: Boolean (true).
     */
    public static final String MINING_EXTRANONCE_SUBSCRIBE = "mining.extranonce.subscribe";

    /**
     * Usage: Miner asks the pool for a specific starting difficulty to skip initial calibration (Vardiff). Often ignored by pools.
     * Response: None / Boolean.
     */
    public static final String MINING_SUGGEST_DIFFICULTY = "mining.suggest_difficulty";

    /**
     * Usage: Pool sends a new block header / job to the miner.
     * Contains the 'clean_jobs' flag telling the miner to drop current work and start immediately on the new job.
     */
    public static final String MINING_NOTIFY = "mining.notify";

    /**
     * Usage: Pool informs the miner that the difficulty for future jobs has changed (Vardiff adjustment).
     */
    public static final String MINING_SET_DIFFICULTY = "mining.set_difficulty";

    /**
     * Usage: Pool assigns a new extranonce1 to the miner on the fly.
     * Usually happens when the old extranonce2 space is exhausted, or the connection is resumed.
     */
    public static final String MINING_SET_EXTRANONCE = "mining.set_extranonce";

    /**
     * Usage: Pool requests the miner to disconnect and reconnect to a new host/port (e.g., for load balancing or maintenance).
     */
    public static final String CLIENT_RECONNECT = "client.reconnect";

    /**
     * Usage: Pool sends a human-readable text message to be displayed in the miner's console or web interface.
     */
    public static final String CLIENT_SHOW_MESSAGE = "client.show_message";

    /**
     * Usage: Pool asks the miner for its exact software version and hardware specification.
     */
    public static final String CLIENT_GET_VERSION = "client.get_version";
}