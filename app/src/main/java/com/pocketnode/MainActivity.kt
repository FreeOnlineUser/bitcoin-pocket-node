package com.pocketnode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pocketnode.ui.mempool.MempoolScreen
import com.pocketnode.ui.mempool.TransactionSearchScreen
import com.pocketnode.ui.theme.BitcoinPocketNodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            BitcoinPocketNodeTheme {
                BitcoinPocketNodeApp()
            }
        }
    }
}

@Composable
fun BitcoinPocketNodeApp() {
    val navController = rememberNavController()
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        NavHost(
            navController = navController,
            startDestination = "mempool"
        ) {
            composable("mempool") {
                MempoolScreen(
                    onNavigateToTransactionSearch = {
                        navController.navigate("transaction_search")
                    }
                )
            }
            
            composable("transaction_search") {
                TransactionSearchScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}