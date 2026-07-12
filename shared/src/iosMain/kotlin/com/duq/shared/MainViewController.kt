package com.duq.shared

import androidx.compose.ui.window.ComposeUIViewController

/** iOS entry-point: Swift-сторона (iosApp) хостит этот UIViewController. */
@Suppress("FunctionNaming")
fun MainViewController() = ComposeUIViewController { App() }
