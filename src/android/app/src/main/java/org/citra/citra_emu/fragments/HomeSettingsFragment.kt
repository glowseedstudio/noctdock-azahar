// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.HomeNavigationDirections
import org.citra.citra_emu.R
import org.citra.citra_emu.adapters.HomeSettingAdapter
import org.citra.citra_emu.databinding.DialogSoftwareKeyboardBinding
import org.citra.citra_emu.databinding.FragmentHomeSettingsBinding
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.SettingKeys
import org.citra.citra_emu.features.settings.ui.SettingsActivity
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.model.HomeSetting
import org.citra.citra_emu.noctdock.NoctDockAvailabilityChecker
import org.citra.citra_emu.noctdock.BottomScreenAutoDimMode
import org.citra.citra_emu.noctdock.NoctDockBridgeSettings
import org.citra.citra_emu.noctdock.NoctDockExportSettingsResolver
import org.citra.citra_emu.noctdock.NoctDockStreamWatch
import org.citra.citra_emu.ui.main.MainActivity
import org.citra.citra_emu.utils.GameHelper
import org.citra.citra_emu.utils.PermissionsHandler
import org.citra.citra_emu.viewmodel.HomeViewModel
import org.citra.citra_emu.utils.GpuDriverHelper
import org.citra.citra_emu.utils.Log
import org.citra.citra_emu.viewmodel.DriverViewModel

class HomeSettingsFragment : Fragment() {
    private var _binding: FragmentHomeSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var mainActivity: MainActivity

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val driverViewModel: DriverViewModel by activityViewModels()

    private val preferences get() =
        PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeSettingsBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mainActivity = requireActivity() as MainActivity

        val optionsList = listOf(
            HomeSetting(
                R.string.grid_menu_core_settings,
                R.string.settings_description,
                R.drawable.ic_settings,
                { SettingsActivity.launch(requireContext(), SettingsFile.FILE_NAME_CONFIG, "") }
            ),
            HomeSetting(
                R.string.artic_base_connect,
                R.string.artic_base_connect_description,
                R.drawable.ic_network,
                {
                    val inflater = LayoutInflater.from(context)
                    val inputBinding = DialogSoftwareKeyboardBinding.inflate(inflater)
                    var textInputValue: String = preferences.getString(SettingKeys.last_artic_base_addr(), "")!!

                    inputBinding.editTextInput.setText(textInputValue)
                    inputBinding.editTextInput.doOnTextChanged { text, _, _, _ ->
                        textInputValue = text.toString()
                    }

                    val dialog = context?.let {
                        MaterialAlertDialogBuilder(it)
                            .setView(inputBinding.root)
                            .setTitle(getString(R.string.artic_base_enter_address))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                if (textInputValue.isNotEmpty()) {
                                    preferences.edit()
                                        .putString(SettingKeys.last_artic_base_addr(), textInputValue)
                                        .apply()
                                    val menu = Game(
                                        title = getString(R.string.artic_base),
                                        path = "articbase://$textInputValue",
                                        filename = ""
                                    )
                                    val action =
                                        HomeNavigationDirections.actionGlobalEmulationActivity(menu)
                                    binding.root.findNavController().navigate(action)
                                }
                            }
                            .setNegativeButton(android.R.string.cancel) {_, _ -> }
                            .show()
                    }
                }
            ),
            HomeSetting(
                R.string.noctdock_3ds_mode_title,
                R.string.noctdock_3ds_mode_description,
                R.drawable.ic_network,
                { showNoctDockSettings() },
                { NoctDockAvailabilityChecker.isSenderInstalled(requireContext()) },
                R.string.noctdock_3ds_mode_not_found,
                R.string.noctdock_3ds_mode_not_found_description
            ),
            HomeSetting(
                R.string.install_game_content,
                R.string.install_game_content_description,
                R.drawable.ic_install,
                { mainActivity.ciaFileInstaller.launch(true) }
            ),
            HomeSetting(
                R.string.setup_system_files,
                R.string.setup_system_files_description,
                R.drawable.ic_system_update,
                {
                    exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
                    parentFragmentManager.primaryNavigationFragment?.findNavController()
                        ?.navigate(R.id.action_homeSettingsFragment_to_systemFilesFragment)
                }
            ),
            HomeSetting(
                R.string.share_log,
                R.string.share_log_description,
                R.drawable.ic_share,
                { shareLog() }
            ),
            HomeSetting(
                R.string.gpu_driver_manager,
                R.string.install_gpu_driver_description,
                R.drawable.ic_install_driver,
                {
                    binding.root.findNavController()
                        .navigate(R.id.action_homeSettingsFragment_to_driverManagerFragment)
                },
                { GpuDriverHelper.supportsCustomDriverLoading() },
                R.string.custom_driver_not_supported,
                R.string.custom_driver_not_supported_description,
                driverViewModel.selectedDriverMetadata
            ),
            HomeSetting(
                R.string.select_citra_user_folder,
                R.string.select_citra_user_folder_home_description,
                R.drawable.ic_home,
                { PermissionsHandler.compatibleSelectDirectory(mainActivity.openCitraDirectory) },
                details = homeViewModel.userDir
            ),
            HomeSetting(
                R.string.select_games_folder,
                R.string.select_games_folder_description,
                R.drawable.ic_add,
                { getGamesDirectory.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data) },
                details = homeViewModel.gamesDir
            ),
            HomeSetting(
                R.string.preferences_theme,
                R.string.theme_and_color_description,
                R.drawable.ic_palette,
                { SettingsActivity.launch(requireContext(), Settings.SECTION_THEME, "") }
            ),
            HomeSetting(
                R.string.about,
                R.string.about_description,
                R.drawable.ic_info_outline,
                {
                    exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
                    parentFragmentManager.primaryNavigationFragment?.findNavController()
                        ?.navigate(R.id.action_homeSettingsFragment_to_aboutFragment)
                }
            )
        )

        binding.homeSettingsList.apply {
            layoutManager = GridLayoutManager(
                requireContext(),
                resources.getInteger(R.integer.game_grid_columns)
            )
            adapter = HomeSettingAdapter(
                requireActivity() as AppCompatActivity,
                viewLifecycleOwner,
                optionsList
            )
        }

        setInsets()
    }

    private fun showNoctDockSettings() {
        val bridgeSettings = NoctDockBridgeSettings(requireContext())
        val behaviorOptions = arrayOf(
            getString(R.string.noctdock_3ds_mode_ask_each_time),
            getString(R.string.noctdock_3ds_mode_always_from_noctdock),
            getString(R.string.noctdock_3ds_mode_play_normally)
        )
        val selectedBehavior = when {
            !bridgeSettings.enabled -> 2
            bridgeSettings.launchBehavior == NoctDockBridgeSettings.LaunchBehavior.ALWAYS_SEND_FROM_NOCTDOCK -> 1
            bridgeSettings.launchBehavior == NoctDockBridgeSettings.LaunchBehavior.PLAY_NORMALLY -> 2
            else -> 0
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.noctdock_3ds_mode_title)
            .setMessage(R.string.noctdock_3ds_mode_description)
            .setSingleChoiceItems(behaviorOptions, selectedBehavior) { dialog, which ->
                when (which) {
                    0 -> {
                        bridgeSettings.enabled = true
                        bridgeSettings.launchBehavior =
                            NoctDockBridgeSettings.LaunchBehavior.ASK_EACH_TIME
                    }
                    1 -> {
                        bridgeSettings.enabled = true
                        bridgeSettings.launchBehavior =
                            NoctDockBridgeSettings.LaunchBehavior.ALWAYS_SEND_FROM_NOCTDOCK
                    }
                    else -> {
                        bridgeSettings.enabled = false
                        bridgeSettings.launchBehavior =
                            NoctDockBridgeSettings.LaunchBehavior.PLAY_NORMALLY
                    }
                }
                dialog.dismiss()
            }
            .setNeutralButton(R.string.noctdock_3ds_mode_export_settings) { _, _ ->
                showNoctDockExportSettings()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun showNoctDockExportSettings() {
        val bridgeSettings = NoctDockBridgeSettings(requireContext())
        if (!bridgeSettings.exportQualityGuideShown) {
            bridgeSettings.exportQualityGuideShown = true
            showNoctDockExportQualityGuide { showNoctDockExportSettingsMenu() }
            return
        }
        showNoctDockExportSettingsMenu()
    }

    private fun showNoctDockExportSettingsMenu() {
        val bridgeSettings = NoctDockBridgeSettings(requireContext())
        val options = arrayOf(
            getString(R.string.noctdock_3ds_mode_performance),
            getString(R.string.noctdock_3ds_mode_resolution),
            getString(R.string.noctdock_3ds_mode_fps),
            getString(R.string.noctdock_3ds_mode_quality_guide_menu),
            getString(R.string.noctdock_bottom_screen_auto_dim_title),
            getString(R.string.noctdock_stream_watch_title)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.noctdock_3ds_mode_export_settings)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showNoctDockPerformanceSettings()
                    1 -> showNoctDockResolutionSettings()
                    2 -> showNoctDockFpsSettings()
                    3 -> showNoctDockExportQualityGuide()
                    4 -> showNoctDockBottomScreenAutoDimSettings()
                    else -> showNoctDockStreamWatchSettings()
                }
            }
            .setPositiveButton(R.string.noctdock_3ds_mode_enable) { _, _ ->
                bridgeSettings.enabled = true
                if (bridgeSettings.launchBehavior == NoctDockBridgeSettings.LaunchBehavior.PLAY_NORMALLY) {
                    bridgeSettings.launchBehavior =
                        NoctDockBridgeSettings.LaunchBehavior.ASK_EACH_TIME
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun showNoctDockExportQualityGuide(onDismiss: (() -> Unit)? = null) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.noctdock_3ds_mode_quality_guide_title)
            .setMessage(R.string.noctdock_3ds_mode_quality_guide_message)
            .setPositiveButton(R.string.noctdock_3ds_mode_quality_guide_got_it) { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setOnCancelListener { onDismiss?.invoke() }
            .show()
    }

    private fun showNoctDockPerformanceSettings() {
        val bridgeSettings = NoctDockBridgeSettings(requireContext())
        val values = NoctDockBridgeSettings.ExportPerformanceMode.entries
        val labels = arrayOf(
            getString(R.string.noctdock_3ds_mode_perf_battery),
            getString(R.string.noctdock_3ds_mode_perf_balanced),
            getString(R.string.noctdock_3ds_mode_perf_sharp),
            getString(R.string.noctdock_3ds_mode_perf_tv),
            getString(R.string.noctdock_3ds_mode_perf_experimental)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.noctdock_3ds_mode_performance)
            .setSingleChoiceItems(labels, values.indexOf(bridgeSettings.exportPerformanceMode)) { dialog, which ->
                NoctDockExportSettingsResolver.applyPerformanceMode(
                    bridgeSettings,
                    values[which],
                )
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun showNoctDockBottomScreenAutoDimSettings() {
        val bridgeSettings = NoctDockBridgeSettings(requireContext())
        val values = BottomScreenAutoDimMode.entries
        val labels = arrayOf(
            getString(R.string.noctdock_bottom_screen_auto_dim_off),
            getString(R.string.noctdock_bottom_screen_auto_dim_gentle),
            getString(R.string.noctdock_bottom_screen_auto_dim_dark),
            getString(R.string.noctdock_bottom_screen_auto_dim_maximum)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.noctdock_bottom_screen_auto_dim_title)
            .setMessage(R.string.noctdock_bottom_screen_auto_dim_description)
            .setSingleChoiceItems(labels, values.indexOf(bridgeSettings.bottomScreenAutoDimMode)) { dialog, which ->
                bridgeSettings.bottomScreenAutoDimMode = values[which]
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun showNoctDockStreamWatchSettings() {
        val bridgeSettings = NoctDockBridgeSettings(requireContext())
        val enableLabel =
            if (bridgeSettings.streamWatchEnabled) {
                getString(R.string.noctdock_stream_watch_disable)
            } else {
                getString(R.string.noctdock_stream_watch_enable)
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.noctdock_stream_watch_title)
            .setMessage(R.string.noctdock_stream_watch_warning)
            .setPositiveButton(enableLabel) { _, _ ->
                bridgeSettings.streamWatchEnabled = !bridgeSettings.streamWatchEnabled
                if (!bridgeSettings.streamWatchEnabled) {
                    NoctDockStreamWatch.stop()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun showNoctDockResolutionSettings() {
        val bridgeSettings = NoctDockBridgeSettings(requireContext())
        val values = NoctDockBridgeSettings.ExportResolution.entries
        val labels = arrayOf(
            getString(R.string.noctdock_3ds_mode_res_auto),
            getString(R.string.noctdock_3ds_mode_res_native),
            getString(R.string.noctdock_3ds_mode_res_sharp),
            getString(R.string.noctdock_3ds_mode_res_tv)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.noctdock_3ds_mode_resolution)
            .setSingleChoiceItems(labels, values.indexOf(bridgeSettings.exportResolution)) { dialog, which ->
                bridgeSettings.exportResolution = values[which]
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun showNoctDockFpsSettings() {
        val bridgeSettings = NoctDockBridgeSettings(requireContext())
        val values = NoctDockBridgeSettings.ExportFps.entries
        val labels = arrayOf(
            getString(R.string.noctdock_3ds_mode_fps_safe),
            getString(R.string.noctdock_3ds_mode_fps_normal)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.noctdock_3ds_mode_fps)
            .setSingleChoiceItems(labels, values.indexOf(bridgeSettings.exportFps)) { dialog, which ->
                bridgeSettings.exportFps = values[which]
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    override fun onStart() {
        super.onStart()
        exitTransition = null
        homeViewModel.setNavigationVisibility(visible = true, animated = true)
        homeViewModel.setStatusBarShadeVisibility(visible = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private val getGamesDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { result ->
            if (result == null) {
                return@registerForActivityResult
            }

            requireContext().contentResolver.takePersistableUriPermission(
                result,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            // When a new directory is picked, we currently will reset the existing games
            // database. This effectively means that only one game directory is supported.
            preferences.edit()
                .putString(GameHelper.KEY_GAME_PATH, result.toString())
                .apply()

            Toast.makeText(
                CitraApplication.appContext,
                R.string.games_dir_selected,
                Toast.LENGTH_LONG
            ).show()

            homeViewModel.setGamesDir(requireActivity(), result.path!!)
        }

    private fun shareLog() {
        val logDirectory = DocumentFile.fromTreeUri(
            requireContext(),
            PermissionsHandler.citraDirectory
        )?.findFile("log")
        val currentLog = logDirectory?.findFile("azahar_log.txt")
        val oldLog = logDirectory?.findFile("azahar_log.old.txt")

        val intent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
        }
        if (!Log.gameLaunched && oldLog?.exists() == true) {
            intent.putExtra(Intent.EXTRA_STREAM, oldLog.uri)
            startActivity(Intent.createChooser(intent, getText(R.string.share_log)))
        } else if (currentLog?.exists() == true) {
            intent.putExtra(Intent.EXTRA_STREAM, currentLog.uri)
            startActivity(Intent.createChooser(intent, getText(R.string.share_log)))
        } else {
            Toast.makeText(
                requireContext(),
                getText(R.string.share_log_not_found),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { view: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val spacingNavigation = resources.getDimensionPixelSize(R.dimen.spacing_navigation)
            val spacingNavigationRail =
                resources.getDimensionPixelSize(R.dimen.spacing_navigation_rail)

            val leftInsets = barInsets.left + cutoutInsets.left
            val rightInsets = barInsets.right + cutoutInsets.right

            binding.scrollViewSettings.updatePadding(
                top = barInsets.top,
                bottom = barInsets.bottom
            )

            val mlpScrollSettings = binding.scrollViewSettings.layoutParams as MarginLayoutParams
            mlpScrollSettings.leftMargin = leftInsets
            mlpScrollSettings.rightMargin = rightInsets
            binding.scrollViewSettings.layoutParams = mlpScrollSettings

            binding.linearLayoutSettings.updatePadding(bottom = spacingNavigation)

            if (ViewCompat.getLayoutDirection(view) == ViewCompat.LAYOUT_DIRECTION_LTR) {
                binding.linearLayoutSettings.updatePadding(left = spacingNavigationRail)
            } else {
                binding.linearLayoutSettings.updatePadding(right = spacingNavigationRail)
            }

            windowInsets
        }
}
