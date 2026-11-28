package app.zhinengtika.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.zhinengtika.nativeapp.ui.AppRoot
import app.zhinengtika.nativeapp.ui.ZhiNengTiKaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZhiNengTiKaTheme {
                AppRoot(applicationContext)
            }
        }
    }
}
