package com.example.myjarvis.modules.notes;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.example.myjarvis.R;

public class NotesModuleFragment extends Fragment {
    private EditText etNoteInput;
    private Button btnSaveNote;
    private ImageButton btnBack;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_notes_module, container, false);
        etNoteInput = view.findViewById(R.id.etNoteInput);
        btnSaveNote = view.findViewById(R.id.btnSaveNote);
        btnBack = view.findViewById(R.id.btnBack);

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> requireActivity().onBackPressed());
        }

        return view;
    }
}
