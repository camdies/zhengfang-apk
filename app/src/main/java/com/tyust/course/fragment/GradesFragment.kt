package com.tyust.course.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.tyust.course.ui.route.GradesRoute
import com.tyust.course.ui.theme.CourseSelectorTheme

/**
 * Legacy Fragment entry point.
 *
 * The main navigation already uses [GradesRoute].  Keeping an independent
 * network/parser implementation here made its session-expiry and account
 * semantics drift from the Compose route, so this entry point now renders the
 * same shared route instead.
 */
class GradesFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setContent {
            CourseSelectorTheme {
                GradesRoute()
            }
        }
    }
}
