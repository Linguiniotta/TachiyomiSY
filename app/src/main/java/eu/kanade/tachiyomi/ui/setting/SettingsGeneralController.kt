package eu.kanade.tachiyomi.ui.setting

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.support.v7.preference.PreferenceScreen
import android.view.View
import com.afollestad.materialdialogs.MaterialDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.util.LocaleHelper
import exh.ui.lock.FingerLockPreference
import exh.ui.lock.LockPreference
import kotlinx.android.synthetic.main.pref_library_columns.view.*
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys

class SettingsGeneralController : SettingsController() {

    private val db: DatabaseHelper = Injekt.get()

    override fun setupPreferenceScreen(screen: PreferenceScreen) = with(screen) {
        titleRes = R.string.pref_category_general

        listPreference {
            key = Keys.lang
            titleRes = R.string.pref_language
            entryValues = arrayOf("", "ar", "bg", "bn", "ca", "cs", "de", "el", "en-US", "en-GB",
            "es", "fr", "hi", "hu", "in", "it", "ja", "ko", "lv", "ms", "nb-rNO", "nl", "pl", "pt",
            "pt-BR", "ro", "ru", "sr", "sv", "th", "tr", "uk", "vi", "zh-rCN")
            entries = entryValues.map { value ->
                val locale = LocaleHelper.getLocaleFromString(value.toString())
                locale?.getDisplayName(locale)?.capitalize() ?:
                        context.getString(R.string.system_default)
            }.toTypedArray()
            defaultValue = ""
            summary = "%s"

            onChange { newValue ->
                val activity = activity ?: return@onChange false
                val app = activity.application
                LocaleHelper.changeLocale(newValue.toString())
                LocaleHelper.updateConfiguration(app, app.resources.configuration)
                activity.recreate()
                true
            }
        }
        intListPreference {
            key = Keys.theme
            titleRes = R.string.pref_theme
            entriesRes = arrayOf(R.string.light_theme, R.string.dark_theme,
                    R.string.amoled_theme, R.string.darkblue_theme)
            entryValues = arrayOf("1", "2", "3", "4")
            defaultValue = "1"
            summary = "%s"

            onChange {
                activity?.recreate()
                true
            }
        }
        preference {
            titleRes = R.string.pref_library_columns
            onClick {
                LibraryColumnsDialog().showDialog(router)
            }

            fun getColumnValue(value: Int): String {
                return if (value == 0)
                    context.getString(R.string.default_columns)
                else
                    value.toString()
            }

            Observable.combineLatest(
                    preferences.portraitColumns().asObservable(),
                    preferences.landscapeColumns().asObservable(),
                    { portraitCols, landscapeCols -> Pair(portraitCols, landscapeCols) })
                    .subscribeUntilDestroy { (portraitCols, landscapeCols) ->
                        val portrait = getColumnValue(portraitCols)
                        val landscape = getColumnValue(landscapeCols)
                        summary = "${context.getString(R.string.portrait)}: $portrait, " +
                                "${context.getString(R.string.landscape)}: $landscape"
                    }
        }
        intListPreference {
            key = Keys.startScreen
            titleRes = R.string.pref_start_screen
            entriesRes = arrayOf(R.string.label_library, R.string.label_recent_manga,
                    R.string.label_recent_updates)
            entryValues = arrayOf("1", "2", "3")
            defaultValue = "1"
            summary = "%s"
        }
        intListPreference {
            key = Keys.libraryUpdateInterval
            titleRes = R.string.pref_library_update_interval
            entriesRes = arrayOf(R.string.update_never, R.string.update_1hour,
                    R.string.update_2hour, R.string.update_3hour, R.string.update_6hour,
                    R.string.update_12hour, R.string.update_24hour, R.string.update_48hour)
            entryValues = arrayOf("0", "1", "2", "3", "6", "12", "24", "48")
            defaultValue = "0"
            summary = "%s"

            onChange { newValue ->
                // Always cancel the previous task, it seems that sometimes they are not updated.
                LibraryUpdateJob.cancelTask()

                val interval = (newValue as String).toInt()
                if (interval > 0) {
                    LibraryUpdateJob.setupTask(interval)
                }
                true
            }
        }
        multiSelectListPreference {
            key = Keys.libraryUpdateRestriction
            titleRes = R.string.pref_library_update_restriction
            entriesRes = arrayOf(R.string.wifi, R.string.charging)
            entryValues = arrayOf("wifi", "ac")
            summaryRes = R.string.pref_library_update_restriction_summary

            preferences.libraryUpdateInterval().asObservable()
                    .subscribeUntilDestroy { isVisible = it > 0 }

            onChange {
                // Post to event looper to allow the preference to be updated.
                Handler().post { LibraryUpdateJob.setupTask() }
                true
            }
        }
        switchPreference {
            key = Keys.updateOnlyNonCompleted
            titleRes = R.string.pref_update_only_non_completed
            defaultValue = false
        }

        val dbCategories = db.getCategories().executeAsBlocking()

        multiSelectListPreference {
            key = Keys.libraryUpdateCategories
            titleRes = R.string.pref_library_update_categories
            entries = dbCategories.map { it.name }.toTypedArray()
            entryValues = dbCategories.map { it.id.toString() }.toTypedArray()

            preferences.libraryUpdateCategories().asObservable()
                    .subscribeUntilDestroy {
                        val selectedCategories = it
                                .mapNotNull { id -> dbCategories.find { it.id == id.toInt() } }
                                .sortedBy { it.order }

                        summary = if (selectedCategories.isEmpty())
                            context.getString(R.string.all)
                        else
                            selectedCategories.joinToString { it.name }
                    }
        }
        intListPreference {
            key = Keys.defaultCategory
            titleRes = R.string.default_category

            val selectedCategory = dbCategories.find { it.id == preferences.defaultCategory() }
            entries = arrayOf(context.getString(R.string.default_category_summary)) +
                    dbCategories.map { it.name }.toTypedArray()
            entryValues = arrayOf("-1") + dbCategories.map { it.id.toString() }.toTypedArray()
            defaultValue = "-1"
            summary = selectedCategory?.name ?: context.getString(R.string.default_category_summary)

            onChange { newValue ->
                summary = dbCategories.find {
                    it.id == (newValue as String).toInt()
                }?.name ?: context.getString(R.string.default_category_summary)
                true
            }
        }

        // --> EXH
        switchPreference {
            key = Keys.eh_askCategoryOnLongPress
            title = "Long-press favorite button to specify category"
            defaultValue = false
        }

        switchPreference {
            key = Keys.eh_expandFilters
            title = "Expand all search filters by default"
            defaultValue = false
        }

        switchPreference {
            key = Keys.eh_autoSolveCaptchas
            title = "Automatically solve captcha"
            summary = "Use HIGHLY EXPERIMENTAL automatic ReCAPTCHA solver. Will be grayed out if unsupported by your device."
            defaultValue = false
            shouldDisableView = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
        }

        switchPreference {
            key = Keys.eh_incogWebview
            title = "Incognito 'Open in browser'"
            summary = "Prevent pages viewed from the 'Open in browser' menu option from being placed into Chrome's browsing history. May be buggy, some browser features will be unavailable."
            defaultValue = false
        }

        preferenceCategory {
            title = "Application lock"

            LockPreference(context).apply {
                key = "pref_app_lock" // Not persistent so use random key
                isPersistent = false

                addPreference(this)
            }

            FingerLockPreference(context).apply {
                key = "pref_lock_finger" // Not persistent so use random key
                isPersistent = false

                addPreference(this)

                //Call after addPreference
                dependency = "pref_app_lock"
            }

            switchPreference {
                key = Keys.eh_lock_manually

                title = "Lock manually only"
                summary = "Disable automatic app locking. The app can still be locked manually by long-pressing the three-lines/back button in the top left corner."
                defaultValue = false
            }
        }
        // <-- EXH
    }

    class LibraryColumnsDialog : DialogController() {

        private val preferences: PreferencesHelper = Injekt.get()

        private var portrait = preferences.portraitColumns().getOrDefault()
        private var landscape = preferences.landscapeColumns().getOrDefault()

        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            val dialog = MaterialDialog.Builder(activity!!)
                    .title(R.string.pref_library_columns)
                    .customView(R.layout.pref_library_columns, false)
                    .positiveText(android.R.string.ok)
                    .negativeText(android.R.string.cancel)
                    .onPositive { _, _ ->
                        preferences.portraitColumns().set(portrait)
                        preferences.landscapeColumns().set(landscape)
                    }
                    .build()

            onViewCreated(dialog.view)
            return dialog
        }

        fun onViewCreated(view: View) {
            with(view.portrait_columns) {
                displayedValues = arrayOf(context.getString(R.string.default_columns)) +
                        IntRange(1, 10).map(Int::toString)
                value = portrait

                setOnValueChangedListener { _, _, newValue ->
                    portrait = newValue
                }
            }
            with(view.landscape_columns) {
                displayedValues = arrayOf(context.getString(R.string.default_columns)) +
                        IntRange(1, 10).map(Int::toString)
                value = landscape

                setOnValueChangedListener { _, _, newValue ->
                    landscape = newValue
                }
            }
        }

    }

}
