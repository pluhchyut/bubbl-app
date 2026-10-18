package com.talksync

// main app door. compose nav starts here and the permission-heavy screens hang off this.

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.talksync.ui.screens.CardboardConversationScreen
import com.talksync.ui.screens.ConversationScreen
import com.talksync.ui.screens.HistoryScreen
import com.talksync.ui.screens.HomeScreen
import com.talksync.ui.screens.JudgeWalkthroughScreen
import com.talksync.ui.screens.MusicVisualScreen
import com.talksync.ui.screens.SettingsScreen
import com.talksync.ui.screens.SoundDirectionScreen
import com.talksync.ui.theme.TalkSyncTheme
import com.talksync.viewmodel.ConversationViewModel
import com.talksync.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TalkSyncTheme {
                val navController = rememberNavController()
                val conversationViewModel: ConversationViewModel = viewModel()
                val settingsViewModel: SettingsViewModel = viewModel()
                val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
                val functionalityReport by conversationViewModel.functionalityReport.collectAsStateWithLifecycle()

                NavHost(
                    navController = navController,
                    startDestination = "home",
                ) {
                    composable("home") {
                        HomeScreen(
                            functionalityReport = functionalityReport,
                            onStartConversation = { navController.navigate("conversation") },
                            onSoundDirection = { navController.navigate("sound_direction") },
                            onJudgeWalkthrough = { navController.navigate("judge_demo") },
                            onHistory = { navController.navigate("history") },
                            onSettings = { navController.navigate("settings") },
                            onCardboard = { navController.navigate("cardboard") },
                            onMusicMirror = { navController.navigate("music_mirror") },
                        )
                    }
                    composable("music_mirror") {
                        MusicVisualScreen(
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("cardboard") {
                        CardboardConversationScreen(
                            conversationViewModel = conversationViewModel,
                            settings = settings,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("judge_demo") {
                        JudgeWalkthroughScreen(
                            settings = settings,
                            functionalityReport = functionalityReport,
                            onBack = { navController.popBackStack() },
                            onStartConversation = { navController.navigate("conversation") },
                        )
                    }
                    composable("conversation") {
                        ConversationScreen(
                            conversationViewModel = conversationViewModel,
                            settings = settings,
                            onBack = { navController.popBackStack() },
                            onHistory = { navController.navigate("history") },
                        )
                    }
                    composable("sound_direction") {
                        SoundDirectionScreen(
                            conversationViewModel = conversationViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("history") {
                        HistoryScreen(
                            conversationViewModel = conversationViewModel,
                            settings = settings,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            settings = settings,
                            settingsViewModel = settingsViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
