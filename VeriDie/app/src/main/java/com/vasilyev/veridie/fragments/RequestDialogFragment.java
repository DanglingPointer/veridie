package com.vasilyev.veridie.fragments;

import android.app.DialogFragment;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.v7.widget.AppCompatSpinner;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.vasilyev.veridie.R;
import com.vasilyev.veridie.utils.Cast;
import com.vasilyev.veridie.utils.Fonts;

import java.util.Arrays;

public class RequestDialogFragment extends DialogFragment
{
    public interface Callbacks
    {
        void onRequestSubmitted(Cast.Request request);
    }

    public static RequestDialogFragment newInstance()
    {
        return new RequestDialogFragment();
    }

    private static final String TAG = "RequestDialogFragment";
    private Callbacks m_callbacks;

    public RequestDialogFragment()
    {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState)
    {
        View root = inflater.inflate(R.layout.fragment_request_dialog, container, false);

        TextView[] txts = new TextView[]{
                root.findViewById(R.id.txt_request_title),
                root.findViewById(R.id.txt_dice_type),
                root.findViewById(R.id.txt_dice_num),
                root.findViewById(R.id.txt_threshold)
        };
        final Typeface tf = Fonts.forText(getContext());
        for (TextView t : txts)
            t.setTypeface(tf);

        AppCompatSpinner spinnerDiceType = root.findViewById(R.id.spinner_dice_type);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(), android.R.layout.simple_spinner_item, Cast.DICE_TYPES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDiceType.setAdapter(adapter);
        spinnerDiceType.setSelection(Arrays.asList(Cast.DICE_TYPES).indexOf("D6"));

        EditText promptDiceNum = root.findViewById(R.id.prompt_dice_num);
        EditText promptThreshold = root.findViewById(R.id.prompt_threshold);

        Button btnSubmit = root.findViewById(R.id.btn_submit);
        btnSubmit.setTypeface(tf);
        btnSubmit.setOnClickListener((v) -> {
            String d = Cast.DICE_TYPES[spinnerDiceType.getSelectedItemPosition()];
            int count;
            try {
                count = Integer.parseInt(promptDiceNum.getText().toString());
            }
            catch (Exception e) {
                count = 0;
            }
            int threshold;
            try {
                threshold = Integer.parseInt(promptThreshold.getText().toString());
            }
            catch (Exception e) {
                threshold = 0;
            }
            try {
                Cast.Request r = new Cast.Request(d, count, threshold, getContext());
                if (m_callbacks != null)
                    m_callbacks.onRequestSubmitted(r);
                dismiss();
            }
            catch (IllegalArgumentException e) {
                Toast.makeText(getActivity().getApplicationContext(), e.getMessage(), Toast.LENGTH_LONG)
                        .show();
            }
        });

        return root;
    }

    @Override
    public void onAttach(Context context)
    {
        super.onAttach(context);
        m_callbacks = (Callbacks)context;
    }

    @Override
    public void onDetach()
    {
        super.onDetach();
        m_callbacks = null;
    }
}
