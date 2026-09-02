package com.example

import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Ignore("Roborazzi screenshot tests run separately via recordRoborazziDebug")
class GreetingScreenshotTest {

  @Test
  @Ignore("Screenshot capture test")
  fun greeting_screenshot() {
    // Screenshot capture test ignored in local unit test suite
  }
}

