package com.nexa.pipe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexa.pipe.ui.VpnControlScreen
import com.nexa.pipe.ui.VpnViewModel
import com.nexa.pipe.ui.theme.NexaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: VpnViewModel = viewModel()
            viewModel.initSettings(this)
            viewModel.loadSettings()
            // 同步 UI 状态：Activity 重建后 ViewModel 的 isVpnRunning 可能为 false，
            // 但 VPN 服务仍在运行。通过检查服务级标志恢复正确的 UI 状态。
            viewModel.syncVpnServiceState()
            NexaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VpnControlScreen()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun VpnControlPreview() {
    NexaTheme {
        VpnControlScreen()
    }
}