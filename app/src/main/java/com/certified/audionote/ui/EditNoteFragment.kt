/*
 * Copyright (c) 2021 Samson Achiaga
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.certified.audionote.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.certified.audionote.R
import com.certified.audionote.database.Repository
import com.certified.audionote.databinding.DialogEditReminderBinding
import com.certified.audionote.databinding.FragmentEditNoteBinding
import com.certified.audionote.model.Note
import com.certified.audionote.utils.*
import com.certified.audionote.utils.Extensions.showToast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class EditNoteFragment : Fragment(), View.OnClickListener, DatePickerDialog.OnDateSetListener,
    TimePickerDialog.OnTimeSetListener {

    private var _binding: FragmentEditNoteBinding? = null
    private val binding: FragmentEditNoteBinding?
        get() = _binding

    @Inject
    lateinit var repository: Repository
    private val viewModel: NotesViewModel by viewModels()
    private lateinit var navController: NavController
    private val args: EditNoteFragmentArgs by navArgs()
    private lateinit var _note: Note
    private var isRecording = false
    private var isPlayingRecord = false
    private lateinit var pickedDateTime: Calendar
    private lateinit var currentDateTime: Calendar
    private var mediaRecorder: MediaRecorder? = null
    private val files: Array<String> by lazy { requireContext().fileList() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentEditNoteBinding.inflate(layoutInflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        navController = Navigation.findNavController(view)
        _note = args.note
        binding?.lifecycleOwner = this
        binding?.apply {
            note = _note
            uiState = viewModel.uiState
            reminderAvailableState = viewModel.reminderAvailableState
            reminderCompletionState = viewModel.reminderCompletionState
        }

        binding?.apply {

            btnBack.setOnClickListener {
                navController.navigate(R.id.action_editNoteFragment_to_homeFragment)
            }
            cardAddReminder.setOnClickListener {
                if (viewModel.reminderAvailableState.get() == ReminderAvailableState.NO_REMINDER)
                    pickDate()
                else
                    openEditReminderDialog()
            }
            btnShare.setOnClickListener { shareNote(_note) }
            btnDelete.setOnClickListener { launchDeleteNoteDialog(_note) }
            btnRecord.setOnClickListener(this@EditNoteFragment)
            fabSaveNote.setOnClickListener(this@EditNoteFragment)

            if (args.note.id == 0) {
                viewModel.uiState.set(UIState.EMPTY)
                chronometerNoteTimer.base = SystemClock.elapsedRealtime()
            } else {
                viewModel.apply {
                    uiState.set(UIState.HAS_DATA)
                    getNote(args.note.id).observe(viewLifecycleOwner) {
                        if (it.reminder != null) {
                            reminderAvailableState.set(ReminderAvailableState.HAS_REMINDER)
                            if (currentDate().timeInMillis > args.note.reminder!!) {
                                reminderCompletionState.set(ReminderCompletionState.COMPLETED)
                            } else {
                                reminderCompletionState.set(ReminderCompletionState.ONGOING)
                            }
                        } else {
                            reminderAvailableState.set(ReminderAvailableState.NO_REMINDER)
                        }
                    }
                }
                tvTitle.text = getString(R.string.edit_note)
                btnRecord.setImageDrawable(
                    ResourcesCompat.getDrawable(
                        resources,
                        R.drawable.ic_audio_not_playing,
                        null
                    )
                )
                binding?.chronometerNoteTimer?.text = args.note.audioLength
//                    binding?.chronometerNoteTimer?.base = setBase(note.audioLength)!!.toLong()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        updateStatusBarColor(binding!!.note!!.color)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onClick(p0: View?) {
        binding?.apply {
            if (args.note.id == 0) {
                when (p0) {
                    btnRecord -> {
                        if (!isRecording) {
                            if (hasPermission(requireContext(), Manifest.permission.RECORD_AUDIO))
                                if (etNoteTitle.text.toString().isNotBlank())
                                    btnRecord.setImageDrawable(
                                        ResourcesCompat.getDrawable(
                                            resources,
                                            R.drawable.ic_mic_recording,
                                            null
                                        )
                                    ).run {
                                        isRecording = true
                                        startRecording()
                                    }
                                else {
                                    showToast("The note title is required")
                                    etNoteTitle.requestFocus()
                                }
                            else
                                requestPermission(
                                    requireActivity(),
                                    "This permission is required to enable audio recording",
                                    MainActivity.RECORD_AUDIO_PERMISSION_CODE,
                                    Manifest.permission.RECORD_AUDIO
                                )
                        } else {
                            btnRecord.setImageDrawable(
                                ResourcesCompat.getDrawable(
                                    resources, R.drawable.ic_mic_not_recording, null
                                )
                            )
                                .run {
                                    isRecording = false
                                    stopRecording()
                                }
                        }
                    }
                    fabSaveNote -> {
                        val note = _note.copy(
                            title = etNoteTitle.text.toString().trim(),
                            description = etNoteDescription.text.toString().trim()
                        )
                        if (note.title.isNotBlank()) {
                            if (isRecording)
                                showToast("Stop the recording first")
                            else {
                                viewModel.insertNote(note)
                                showToast("Note saved")
                                navController.navigate(R.id.action_editNoteFragment_to_homeFragment)
                            }
                        } else {
                            showToast("The note title is required")
                            etNoteTitle.requestFocus()
                        }
                    }
                }
            } else {
                when (p0) {
                    btnRecord -> {
                        if (!isPlayingRecord) {
                            btnRecord.setImageDrawable(
                                ResourcesCompat.getDrawable(
                                    resources,
                                    R.drawable.ic_audio_playing, null
                                )
                            )
                                .run {
                                    isPlayingRecord = true
                                    startPlayingRecording()
                                }
                        } else {
                            btnRecord.setImageDrawable(
                                ResourcesCompat.getDrawable(
                                    resources,
                                    R.drawable.ic_audio_not_playing, null
                                )
                            )
                                .run {
                                    isPlayingRecord = false
                                    stopPlayingRecording()
                                }
                        }
                    }
                    fabSaveNote -> {
                        val note = _note.copy(
                            title = etNoteTitle.text.toString().trim(),
                            description = etNoteDescription.text.toString().trim(),
                            lastModificationDate = currentDate().timeInMillis
                        )
                        if (note.title.isNotBlank()) {
                            viewModel.updateNote(note)
                            navController.navigate(R.id.action_editNoteFragment_to_homeFragment)
                        } else {
                            showToast("The note title is required")
                            etNoteTitle.requestFocus()
                        }
                    }
                }
            }
        }
    }

    override fun onDateSet(p0: DatePicker?, p1: Int, p2: Int, p3: Int) {
        pickedDateTime = currentDate()
        pickedDateTime.set(p1, p2, p3)
        currentDateTime = currentDate()
        val hourOfDay = currentDateTime.get(Calendar.HOUR_OF_DAY)
        val minuteOfDay = currentDateTime.get(Calendar.MINUTE)
        val timePickerDialog =
            TimePickerDialog(requireContext(), this, hourOfDay, minuteOfDay, false)
        timePickerDialog.show()
    }

    override fun onTimeSet(p0: TimePicker?, p1: Int, p2: Int) {
        pickedDateTime.set(Calendar.HOUR_OF_DAY, p1)
        pickedDateTime.set(Calendar.MINUTE, p2)
        if (pickedDateTime.timeInMillis <= currentDate().timeInMillis) {
            pickedDateTime.run {
                set(Calendar.DAY_OF_MONTH, currentDateTime.get(Calendar.DAY_OF_MONTH) + 1)
                set(Calendar.YEAR, currentDateTime.get(Calendar.YEAR))
                set(Calendar.MONTH, currentDateTime.get(Calendar.MONTH))
            }
        }
        _note.reminder = pickedDateTime.timeInMillis
        viewModel.updateNote(_note)
    }

    private fun pickDate() {
        currentDateTime = currentDate()
        val startYear = currentDateTime.get(Calendar.YEAR)
        val startMonth = currentDateTime.get(Calendar.MONTH)
        val startDay = currentDateTime.get(Calendar.DAY_OF_MONTH)
        val datePickerDialog =
            DatePickerDialog(requireContext(), this, startYear, startMonth, startDay)
        datePickerDialog.show()
    }

    private fun openEditReminderDialog() {
        val view = DialogEditReminderBinding.inflate(layoutInflater)
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        view.apply {
            note = _note
            btnDeleteReminder.setOnClickListener {
                viewModel.reminderCompletionState.set(ReminderCompletionState.ONGOING)
                _note.reminder = null
                viewModel.updateNote(_note)
                bottomSheetDialog.dismiss()
            }
            btnModifyReminder.setOnClickListener {
                bottomSheetDialog.dismiss()
                pickDate()
            }
        }
        bottomSheetDialog.edgeToEdgeEnabled
        bottomSheetDialog.setContentView(view.root)
        bottomSheetDialog.show()
    }

    private fun launchDeleteNoteDialog(note: Note) {
        val materialDialog = MaterialAlertDialogBuilder(requireContext())
        materialDialog.apply {
            setTitle("Delete Note")
            setMessage("Are you sure you want to delete ${note.title}?")
            setNegativeButton("No") { dialog, _ -> dialog?.dismiss() }
            setPositiveButton("Yes") { _, _ ->
                viewModel.deleteNote(note)
                navController.navigate(R.id.action_editNoteFragment_to_homeFragment)
            }
            show()
        }
    }

    private fun startRecording() {
        binding?.chronometerNoteTimer?.base = SystemClock.elapsedRealtime()
        binding?.chronometerNoteTimer?.start()
        val filePath = filePath(requireActivity())
        val fileName = "${binding?.etNoteTitle?.text.toString().trim()}.3gp"
        showToast("Started recording")
        mediaRecorder = MediaRecorder()
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setOutputFile("$filePath/$fileName")
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)

            try {
                prepare()
                start()
            } catch (e: IOException) {
                showToast("An error occurred")
            }

        }
    }

    private fun stopRecording() {
        binding?.chronometerNoteTimer?.stop()
        _note.audioLength = binding?.chronometerNoteTimer?.text.toString()
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        showToast("Stopped recording")
    }

    //    @RequiresApi(Build.VERSION_CODES.N)
    private fun startPlayingRecording() {
        binding?.chronometerNoteTimer?.isCountDown = true
        showToast("Started playing recording")
    }

    private fun stopPlayingRecording() {
        showToast("Stopped playing recording")
    }

    private fun shareNote(note: Note) {
//        TODO("Not yet Implemented")
        showToast("Coming soon: ${note.title}")
    }

    private fun updateStatusBarColor(color: Int) {
        val window = requireActivity().window
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
//        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.statusBarColor = color
    }

//    private fun setBase(value: String): String? {
//        var base = ""
//        value.forEach {
//
//        }
//        return if (value.length >= 5) {
//            for (i in 0..4)
//                if (i == 3)
//                    continue
//            base += value[i]
//            base
//        } else null
//    }
}