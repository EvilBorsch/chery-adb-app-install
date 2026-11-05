package local.desaysv.apk.installer

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // Интерфейс всегда светлый, поэтому и значки системных панелей всегда тёмные.
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    super.onCreate(savedInstanceState)

    // С Android 15 окно всегда рисуется под системными панелями, а CSS safe-area в WebView
    // поддерживается не везде — поэтому отступ от панелей и выреза даёт само окно.
    val content = findViewById<View>(android.R.id.content)
    content.setBackgroundColor(Color.rgb(0xee, 0xf1, 0xf5))
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
      val bars = insets.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
      )
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      WindowInsetsCompat.CONSUMED
    }
  }
}
