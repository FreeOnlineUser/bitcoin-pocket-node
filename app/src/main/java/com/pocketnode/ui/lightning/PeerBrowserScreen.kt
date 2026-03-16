package com.pocketnode.ui.lightning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.platform.LocalContext
import com.pocketnode.lightning.NodeDirectory
import com.pocketnode.lightning.NodeDirectory.LightningNode
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browse and search Lightning network peers.
 * Uses mempool.space API for node directory -- the only third-party/centralized
 * dependency in the app. Optional: users can enter node pubkeys manually instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerBrowserScreen(
    onNavigateBack: () -> Unit = {},
    onSelectNode: (nodeId: String, address: String, alias: String, minChannelSats: Long) -> Unit = { _, _, _, _ -> }
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf<List<LightningNode>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Most Connected", "Largest", "Search")
    var lastUpdate by remember { mutableStateOf(getCacheAge(context)) }
    var enrichKey by remember { mutableStateOf(0) }

    // Load from cache first, fetch from network only if no cache
    LaunchedEffect(selectedTab) {
        if (selectedTab < 2) {
            loading = true
            val cached = loadCachedNodes(context, selectedTab)
            if (cached.isNotEmpty()) {
                nodes = cached
                loading = false
            } else {
                nodes = withContext(Dispatchers.IO) {
                    val fetched = when (selectedTab) {
                        0 -> NodeDirectory.getTopNodes(30)
                        1 -> NodeDirectory.getTopByCapacity(30)
                        else -> emptyList()
                    }
                    if (fetched.isNotEmpty()) saveCachedNodes(context, selectedTab, fetched)
                    fetched
                }
                loading = false
                lastUpdate = getCacheAge(context)
            }
        }
    }

    // Enrich nodes with anchor data from mempool.space (background)
    LaunchedEffect(enrichKey) {
        val toEnrich = nodes.filter { it.supportsAnchors == null && it.publicKey.isNotEmpty() }
        if (toEnrich.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val enriched = nodes.toMutableList()
                for (i in enriched.indices) {
                    if (enriched[i].supportsAnchors == null && enriched[i].publicKey.isNotEmpty()) {
                        try {
                            val details = NodeDirectory.getNodeDetails(enriched[i].publicKey)
                            if (details?.supportsAnchors != null) {
                                enriched[i] = enriched[i].copy(
                                    supportsAnchors = details.supportsAnchors,
                                    sockets = if (enriched[i].sockets.isEmpty()) details.sockets else enriched[i].sockets
                                )
                                withContext(Dispatchers.Main) { nodes = enriched.toList() }
                            }
                        } catch (_: Exception) {}
                    }
                }
                if (selectedTab < 2) saveCachedNodes(context, selectedTab, enriched)
            }
        }
    }

    fun refreshNodes() {
        loading = true
        scope.launch {
            nodes = withContext(Dispatchers.IO) {
                val fetched = when (selectedTab) {
                    0 -> NodeDirectory.getTopNodes(30)
                    1 -> NodeDirectory.getTopByCapacity(30)
                    else -> emptyList()
                }
                if (fetched.isNotEmpty()) saveCachedNodes(context, selectedTab, fetched)
                fetched
            }
            loading = false
            lastUpdate = getCacheAge(context)
            enrichKey++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find Peers") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    com.pocketnode.ui.components.PeerCountBadge()
                    if (selectedTab < 2) {
                        IconButton(onClick = { refreshNodes() }) {
                            Icon(Icons.Default.Refresh, "Refresh from mempool.space")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // Anchor-only filter
            val anchorPrefs = remember { context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE) }
            var anchorOnly by remember { mutableStateOf(anchorPrefs.getBoolean("anchor_channels_only", true)) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Data from mempool.space" + if (lastUpdate.isNotEmpty()) " · $lastUpdate" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚓ Only", style = MaterialTheme.typography.labelSmall)
                    Switch(
                        checked = anchorOnly,
                        onCheckedChange = {
                            anchorOnly = it
                            anchorPrefs.edit().putBoolean("anchor_channels_only", it).apply()
                        },
                        modifier = Modifier.height(32.dp)
                    )
                }
            }
            Text(
                "Anchor channels use zero-fee commitments, eliminating fee disagreements that can force-close your channel. Essential for mobile nodes with intermittent connectivity.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            val displayNodes = if (anchorOnly) nodes.filter { it.supportsAnchors != false } else nodes

            // Search bar (visible on Search tab)
            if (selectedTab == 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Node name or pubkey") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                if (searchQuery.isNotBlank()) {
                                    loading = true
                                    scope.launch {
                                        nodes = withContext(Dispatchers.IO) {
                                            NodeDirectory.search(searchQuery)
                                        }
                                        loading = false
                                        enrichKey++
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Search, "Search")
                            }
                        }
                    )
                }
            }

            // Loading
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFF9800))
                }
            } else if (displayNodes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (selectedTab == 2) "Search for a node by name or pubkey"
                        else if (anchorOnly && nodes.isNotEmpty()) "No anchor-capable peers found"
                        else "No nodes found",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(displayNodes) { node ->
                        NodeCard(node = node, onSelect = {
                            // Need to fetch details for sockets if not available
                            scope.launch {
                                val details = if (node.sockets.isNotEmpty() && node.supportsAnchors != null) node
                                else withContext(Dispatchers.IO) {
                                    NodeDirectory.getNodeDetails(node.publicKey)
                                }
                                if (details != null) {
                                    // Cache anchor status from mempool.space
                                    if (details.supportsAnchors != null) {
                                        context.getSharedPreferences("peer_channel_limits", Context.MODE_PRIVATE)
                                            .edit().putBoolean("${details.publicKey}_anchors", details.supportsAnchors).apply()
                                    }
                                    val addr = if (details.address.isNotEmpty()) details.address else details.sockets
                                    onSelectNode(details.publicKey, addr, details.alias, details.minChannelSize)
                                }
                            }
                        })
                    }
                }
            }
        }
    }
}

private fun formatSats(sats: Long): String {
    return when {
        sats >= 100_000_000 -> "%.2f BTC".format(sats / 100_000_000.0)
        sats >= 1_000_000 -> "%.1fM sats".format(sats / 1_000_000.0)
        sats >= 1_000 -> "${sats / 1_000}k sats"
        else -> "$sats sats"
    }
}

@Composable
private fun NodeCard(
    node: LightningNode,
    onSelect: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("peer_channel_limits", Context.MODE_PRIVATE)
    val cachedMin = prefs.getLong(node.publicKey, -1L)
    val isFloor = prefs.getBoolean("${node.publicKey}_floor", false)
    val isCeiling = prefs.getBoolean("${node.publicKey}_ceiling", false)
    val supportsAnchors = node.supportsAnchors

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        node.alias.ifBlank { "Unknown" },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        node.publicKey.take(16) + "...",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (supportsAnchors != null) {
                            Text(
                                if (supportsAnchors) "⚓" else "⚠️",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            "${node.channels} ch",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        node.capacityBtc,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (node.country.isNotEmpty()) {
                    Text(
                        node.country,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cachedMin > 0) {
                        val suffix = if (isCeiling) "-" else if (isFloor) "+" else ""
                        Text(
                            "${if (isCeiling) "✓" else "min"} ${"%,d".format(cachedMin)}$suffix",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isCeiling) Color(0xFF4CAF50) else Color(0xFF64B5F6)
                        )
                    }
                    if (node.feeRate >= 0) {
                        Text(
                            "${node.feeRate} ppm",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// --- Peer cache helpers ---

private fun cacheKey(tab: Int) = "peers_tab_$tab"

private fun saveCachedNodes(context: Context, tab: Int, nodes: List<LightningNode>) {
    val arr = JSONArray()
    nodes.forEach { n ->
        arr.put(JSONObject().apply {
            put("publicKey", n.publicKey)
            put("alias", n.alias)
            put("channels", n.channels)
            put("capacity", n.capacity)
            put("country", n.country)
            put("feeRate", n.feeRate)
            put("sockets", n.sockets)
            put("minChannelSize", n.minChannelSize)
            if (n.supportsAnchors != null) put("supportsAnchors", n.supportsAnchors)
        })
    }
    context.getSharedPreferences("peer_cache", Context.MODE_PRIVATE).edit()
        .putString(cacheKey(tab), arr.toString())
        .putLong("last_update", System.currentTimeMillis())
        .apply()
}

private fun loadCachedNodes(context: Context, tab: Int): List<LightningNode> {
    val json = context.getSharedPreferences("peer_cache", Context.MODE_PRIVATE)
        .getString(cacheKey(tab), null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LightningNode(
                publicKey = o.getString("publicKey"),
                alias = o.optString("alias", ""),
                channels = o.optInt("channels", 0),
                capacity = o.optLong("capacity", 0),
                country = o.optString("country", ""),
                feeRate = o.optLong("feeRate", -1),
                sockets = o.optString("sockets", ""),
                minChannelSize = o.optLong("minChannelSize", 0),
                supportsAnchors = if (o.has("supportsAnchors")) o.getBoolean("supportsAnchors") else null
            )
        }
    } catch (_: Exception) { emptyList() }
}

private fun getCacheAge(context: Context): String {
    val ts = context.getSharedPreferences("peer_cache", Context.MODE_PRIVATE)
        .getLong("last_update", 0)
    if (ts == 0L) return ""
    val mins = (System.currentTimeMillis() - ts) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 1440 -> "${mins / 60}h ago"
        else -> "${mins / 1440}d ago"
    }
}
