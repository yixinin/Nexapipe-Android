package com.nexa.pipe.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexa.pipe.PermissionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnControlScreen(viewModel: VpnViewModel = viewModel()) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val isVpnRunning by viewModel.isVpnRunning.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val isIrohStarted by viewModel.isIrohStarted.collectAsState()
    val endpointId by viewModel.endpointId.collectAsState()
    val nodes by viewModel.nodes.collectAsState()
    val proxyPort by viewModel.proxyPort.collectAsState()
    val logMessages by viewModel.logMessages.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var showLogs by remember { mutableStateOf(false) }
    var newNodeId by remember { mutableStateOf("") }
    var showPermissionGuide by remember { mutableStateOf(false) }
    var expandedNode by remember { mutableStateOf<String?>(null) }
    var newDomainForNode by remember { mutableStateOf<String?>(null) }
    var focusTrigger by remember { mutableStateOf(0) }
    val domainFocusRequester = remember { FocusRequester() }
    var nodeToDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(focusTrigger) {
        if (focusTrigger > 0) {
            delay(100)
            domainFocusRequester.requestFocus()
        }
    }

    fun handleConnect(context: Context) {
        viewModel.checkVpnPermission(context)
        viewModel.checkNotificationPermission(context)

        val vpnGranted = viewModel.vpnPermissionGranted.value
        val notificationGranted = viewModel.notificationPermissionGranted.value

        if (!vpnGranted || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted)) {
            showPermissionGuide = true
            return
        }

        viewModel.connect(context)
    }

    fun copyToClipboard(text: String, label: String) {
        clipboardManager.setText(AnnotatedString(text))
        Toast.makeText(context, "Copied $label", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nexa VPN") },
                actions = {
                    IconButton(onClick = { showLogs = !showLogs }) {
                        Icon(Icons.Default.Info, contentDescription = "Logs")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (isVpnRunning) {
                        viewModel.disconnect(context)
                    } else {
                        handleConnect(context)
                    }
                },
                containerColor = if (isVpnRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                modifier = Modifier.size(56.dp)
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                            imageVector = if (isVpnRunning) Icons.Default.Close else Icons.Default.CheckCircle,
                            contentDescription = if (isVpnRunning) "Disconnect" else "Connect",
                            modifier = Modifier.size(28.dp)
                        )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (isVpnRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isVpnRunning) "Connected" else if (isConnecting) "Connecting..." else "Disconnected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isVpnRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isVpnRunning) MaterialTheme.colorScheme.primary
                                    else if (isConnecting) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                )
                        )
                    }
                    if (isVpnRunning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Traffic is being routed through iroh",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = isIrohStarted && endpointId.isNotEmpty(),
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Endpoint ID", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { copyToClipboard(endpointId, "Endpoint ID") }) {
                                Icon(Icons.Default.Share, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = endpointId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Settings", style = MaterialTheme.typography.titleMedium)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = { viewModel.updateProxyPort(it) },
                        label = { Text("Local Proxy Port") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                        singleLine = true
                    )

                    HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Nodes & Domains", style = MaterialTheme.typography.titleSmall)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = newNodeId,
                            onValueChange = { newNodeId = it },
                            label = { Text("Add node ID") },
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                if (newNodeId.isNotEmpty()) {
                                    val nodeId = newNodeId
                                    viewModel.addNode(nodeId)
                                    expandedNode = nodeId
                                    newDomainForNode = "$nodeId:"
                                    newNodeId = ""
                                    focusTrigger++
                                }
                            },
                            modifier = Modifier.align(Alignment.Bottom),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (nodes.isEmpty()) {
                        Text(
                            text = "No nodes added. Please add nodes with domains to proxy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(modifier = Modifier.height(320.dp)) {
                            items(nodes) { node ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(
                                                        Icons.Default.Share,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                node.nodeId,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(
                                                onClick = { nodeToDelete = node.nodeId },
                                                colors = ButtonDefaults.textButtonColors(
                                                    contentColor = MaterialTheme.colorScheme.error
                                                )
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Remove node",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Delete", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            val isCurrentNode = expandedNode == node.nodeId
                                            val domainValue = if (isCurrentNode) {
                                                newDomainForNode?.takeIf { it.startsWith(node.nodeId + ":") }?.substringAfter(":") ?: ""
                                            } else ""
                                            OutlinedTextField(
                                                value = domainValue,
                                                onValueChange = { value ->
                                                    expandedNode = node.nodeId
                                                    newDomainForNode = "${node.nodeId}:$value"
                                                },
                                                label = { Text("Add domain") },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 8.dp)
                                                    .then(
                                                        if (isCurrentNode) Modifier.focusRequester(domainFocusRequester)
                                                        else Modifier
                                                    )
                                                    .onFocusChanged { focusState ->
                                                        if (focusState.isFocused) {
                                                            expandedNode = node.nodeId
                                                        }
                                                    },
                                                singleLine = true
                                            )
                                            Button(
                                                onClick = {
                                                    val domain = if (expandedNode == node.nodeId) (newDomainForNode?.substringAfter(":") ?: "") else ""
                                                    if (domain.isNotEmpty()) {
                                                        viewModel.addDomainToNode(node.nodeId, domain)
                                                        newDomainForNode = "${node.nodeId}:"
                                                        expandedNode = node.nodeId
                                                    }
                                                },
                                                modifier = Modifier.align(Alignment.Bottom),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.secondary
                                                )
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(18.dp))
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        if (node.domains.isEmpty()) {
                                            Text(
                                                text = "No domains added for this node.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        } else {
                                            node.domains.forEach { domain ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 4.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.CheckCircle,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(domain, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                                    TextButton(
                                                        onClick = { viewModel.removeDomainFromNode(node.nodeId, domain) },
                                                        colors = ButtonDefaults.textButtonColors(
                                                            contentColor = MaterialTheme.colorScheme.error
                                                        ),
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Close,
                                                            contentDescription = "Remove",
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(2.dp))
                                                        Text("Remove", style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (errorMessage != null) {
                AnimatedVisibility(
                    visible = errorMessage != null,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }

        if (showLogs) {
            ModalBottomSheet(
                onDismissRequest = { showLogs = false },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Logs", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.clearLogs() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                        }
                        IconButton(onClick = { showLogs = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(logMessages) { message ->
                            Text(message, style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }

        if (showPermissionGuide) {
            ModalBottomSheet(
                onDismissRequest = { showPermissionGuide = false },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier.fillMaxHeight(0.9f)
            ) {
                PermissionGuideScreen(
                    viewModel = viewModel,
                    onComplete = {
                        showPermissionGuide = false
                        viewModel.refreshPermissions(context)
                    }
                )
            }
        }

        nodeToDelete?.let { nodeId ->
            AlertDialog(
                onDismissRequest = { nodeToDelete = null },
                title = { Text("Delete Node") },
                text = { Text("Are you sure you want to delete node:\n$nodeId") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.removeNode(nodeId)
                            nodeToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { nodeToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
