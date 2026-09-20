package com.hackmit.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hackmit.app.alerts.AlertEvent
import com.hackmit.app.di.AppContainer
import com.hackmit.app.ui.screens.FaceCalibrationScreen
import com.hackmit.app.ui.screens.FaceTestScreen
import com.hackmit.app.ui.screens.HomeScreen
import com.hackmit.app.ui.screens.MotorCalibrationScreen
import com.hackmit.app.ui.screens.MotorTestScreen
import com.hackmit.app.ui.screens.ResultsScreen
import com.hackmit.app.ui.screens.SettingsScreen
import com.hackmit.app.ui.screens.Simulated911Screen
import com.hackmit.app.ui.screens.SlurDemoScreen
import com.hackmit.app.ui.screens.SpeechCalibrationScreen
import com.hackmit.app.ui.screens.SpeechMonitorScreen
import com.hackmit.app.ui.screens.SpeechTestScreen

object Routes {
    const val HOME = "home"
    const val FACE_CALIB = "face_calibration"
    const val FACE_TEST = "face_test"
    const val SPEECH_CALIB = "speech_calibration"
    const val SPEECH_TEST = "speech_test"
    const val MOTOR_CALIB = "motor_calibration"
    const val MOTOR_TEST = "motor_test"
    const val MONITOR = "monitor"
    const val SLUR_DEMO = "slur_demo"
    const val SIMULATED_911 = "simulated_911"
    const val RESULTS = "results"
    const val SETTINGS = "settings"
}

@Composable
fun StrokeApp(container: AppContainer) {
    val vm: AssessmentViewModel = viewModel(factory = AssessmentViewModelFactory(container))
    val nav = rememberNavController()

    LaunchedEffect(Unit) {
        vm.alertManager.events.collect { event ->
            if (event is AlertEvent.RunFastAssessment) {
                nav.navigate(Routes.FACE_CALIB)
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(vm, nav) }
        composable(Routes.FACE_CALIB) { FaceCalibrationScreen(vm, nav) }
        composable(Routes.FACE_TEST) { FaceTestScreen(vm, nav) }
        composable(Routes.SPEECH_CALIB) { SpeechCalibrationScreen(vm, nav) }
        composable(Routes.SPEECH_TEST) { SpeechTestScreen(vm, nav) }
        composable(Routes.MOTOR_CALIB) { MotorCalibrationScreen(vm, nav) }
        composable(Routes.MOTOR_TEST) { MotorTestScreen(vm, nav) }
        composable(Routes.MONITOR) { SpeechMonitorScreen(vm, nav) }
        composable(Routes.SLUR_DEMO) { SlurDemoScreen(vm, nav) }
        composable(Routes.SIMULATED_911) { Simulated911Screen(vm, nav) }
        composable(Routes.RESULTS) { ResultsScreen(vm, nav) }
        composable(Routes.SETTINGS) { SettingsScreen(vm, nav) }
    }
}
