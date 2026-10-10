package com.smolish

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.NumberPicker
import android.widget.RadioGroup
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.DateFormatSymbols
import java.time.Month
import java.time.MonthDay

/** "Smolish Setup": birthday (no year) and where you celebrate, for the seasonal icons. */
class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.setup_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val day = findViewById<NumberPicker>(R.id.day)
        val month = findViewById<NumberPicker>(R.id.month)
        val noBirthday = findViewById<CheckBox>(R.id.no_birthday)
        val region = findViewById<RadioGroup>(R.id.region)

        month.minValue = 1
        month.maxValue = 12
        month.displayedValues = DateFormatSymbols.getInstance().months.take(12).toTypedArray()
        day.minValue = 1
        // feb 29 is allowed, there's no year
        month.setOnValueChangedListener { _, _, m -> day.maxValue = Month.of(m).maxLength() }

        val saved = Icons.birthday(this)
        month.value = saved?.monthValue ?: 1
        day.maxValue = Month.of(month.value).maxLength()
        day.value = saved?.dayOfMonth ?: 1
        noBirthday.isChecked = saved == null && Icons.setupDone(this)
        noBirthday.setOnCheckedChangeListener { _, off -> day.isEnabled = !off; month.isEnabled = !off }
        day.isEnabled = !noBirthday.isChecked
        month.isEnabled = !noBirthday.isChecked

        region.check(when (Icons.region(this)) {
            Icons.WESTERN -> R.id.region_west
            Icons.NO_RELIGION -> R.id.region_none
            else -> R.id.region_ru
        })

        findViewById<Button>(R.id.done).setOnClickListener {
            Icons.setBirthday(this, if (noBirthday.isChecked) null else MonthDay.of(month.value, day.value))
            Icons.setRegion(this, when (region.checkedRadioButtonId) {
                R.id.region_west -> Icons.WESTERN
                R.id.region_none -> Icons.NO_RELIGION
                else -> Icons.RUSSIA
            })
            Icons.setSetupDone(this)
            finish()
        }
    }

    // leaving setup = a good moment to switch the icon if today is a special day
    override fun onStop() {
        super.onStop()
        if (Icons.setupDone(this)) Icons.apply(this)
    }
}
