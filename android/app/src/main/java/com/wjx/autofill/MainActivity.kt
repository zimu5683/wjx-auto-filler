package com.wjx.autofill

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 占位实现：Phase 0 只用于验证构建链。
 * 真正的双栏配置界面由 android-dev 接手重写（ui/ 包 + res/layout/）。
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
