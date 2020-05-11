package com.vasilyev.veridie.fragments;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.vasilyev.veridie.CastItem;
import com.vasilyev.veridie.R;
import com.vasilyev.veridie.utils.Cast;
import com.vasilyev.veridie.utils.Fonts;

import java.util.ArrayList;
import java.util.List;

public class PlayingFragment extends Fragment
{
    public interface Callbacks
    {
        void onCastRequestPressed();
        void onDetailedViewPressed(CastItem cast);
    }

    private static final String TAG = "PlayingFragment";
    private static final String ARG_GENERATOR_NAME = "PlayingFragment.generator_name";
    private static final String SAVED_CAST_ITEMS = "SAVED_CAST_ITEMS";

    public static PlayingFragment newInstance(String generatorName)
    {
        PlayingFragment fragment = new PlayingFragment();
        Bundle args = new Bundle();
        args.putString(ARG_GENERATOR_NAME, generatorName);
        fragment.setArguments(args);
        return fragment;
    }

    private String m_generatorName;
    private Callbacks m_callbacks;
    private ArrayList<CastItem> m_casts;
    private CastItemAdapter m_adapter;
    private RecyclerView m_recyclerView;

    public PlayingFragment()
    {
    }

    public void addRequest(String from, Cast.Request request)
    {
        m_casts.add(new CastItem(request, from));
        m_adapter.notifyItemInserted(m_casts.size() - 1);
        m_recyclerView.smoothScrollToPosition(m_adapter.getItemCount() - 1);
    }

    public void addResult(String from, Cast.Result result)
    {
        m_casts.add(new CastItem(result, from));
        m_adapter.notifyItemInserted(m_casts.size() - 1);
        m_recyclerView.smoothScrollToPosition(m_adapter.getItemCount() - 1);
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            m_generatorName = getArguments().getString(ARG_GENERATOR_NAME);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState)
    {
        View root = inflater.inflate(R.layout.fragment_playing, container, false);
        Context context = root.getContext();

        if (savedInstanceState != null)
            m_casts = savedInstanceState.getParcelableArrayList(SAVED_CAST_ITEMS);
        if (m_casts == null)
            m_casts = new ArrayList<>();

        TextView generatorText = root.findViewById(R.id.txt_generator_name);
        generatorText.setTypeface(Fonts.forText(context));
        generatorText.setText(context.getString(R.string.play_txt_generator, m_generatorName));

        Button requestBtn = root.findViewById(R.id.btn_cast_request);
        requestBtn.setTypeface(Fonts.forText(context));
        requestBtn.setOnClickListener(v -> m_callbacks.onCastRequestPressed());

        m_recyclerView = root.findViewById(R.id.list_casts);
        m_recyclerView.setLayoutManager(new LinearLayoutManager(context));
        m_adapter = new CastItemAdapter(m_casts, m_callbacks);
        m_recyclerView.setAdapter(m_adapter);
        return root;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState)
    {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList(SAVED_CAST_ITEMS, m_casts);
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

class CastItemAdapter extends RecyclerView.Adapter<CastItemAdapter.ViewHolder>
{
    private final List<CastItem> m_items;
    private final PlayingFragment.Callbacks m_callbacks;

    public CastItemAdapter(List<CastItem> items, PlayingFragment.Callbacks listener)
    {
        m_items = items;
        m_callbacks = listener;
    }

    @Override
    public @NonNull ViewHolder onCreateViewHolder(ViewGroup parent, int viewType)
    {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.list_item_cast, parent, false);
        return new ViewHolder(view, parent.getContext());
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position)
    {
        CastItem item = m_items.get(position);
        if (item.getCast() instanceof Cast.Request) {
            Cast.Request request = (Cast.Request)item.getCast();
            holder.bindToRequest(item.getFrom(), request);
        } else if (item.getCast() instanceof Cast.Result) {
            Cast.Result result = (Cast.Result)item.getCast();
            holder.bindToResult(item.getFrom(), result);
        }
        holder.setOnClickListener((v) -> m_callbacks.onDetailedViewPressed(item));
    }

    @Override
    public int getItemCount()
    {
        return m_items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder
    {
        private final Context m_context;
        private final View m_root;
        private final TextView m_description;
        private final TextView m_content;

        ViewHolder(View view, Context ctx)
        {
            super(view);
            m_context = ctx;
            m_root = view;
            m_description = view.findViewById(R.id.item_number);
            m_description.setTypeface(Fonts.forText(ctx));
            m_content = view.findViewById(R.id.content);
            m_content.setTypeface(Fonts.forText(ctx));
        }

        void bindToRequest(String from, Cast.Request request)
        {
            String txt;
            if (request.hasThreshold())
                txt = m_context.getString(R.string.play_request_w_threshold,
                        from, request.getCount(), request.getD(), request.getSuccessFrom());
            else
                txt = m_context.getString(R.string.play_request_wo_threshold,
                        from, request.getCount(), request.getD());
            m_description.setText(txt);
            m_content.setText(null);
            m_content.setVisibility(View.GONE);
        }
        void bindToResult(String from, Cast.Result result)
        {
            String txt;
            if (result.hasSuccessCount())
                txt = m_context.getString(R.string.play_result_w_count,
                        result.getValues().length, result.getD(), result.getSuccessCount());
            else
                txt = m_context.getString(R.string.play_result_wo_count,
                        result.getValues().length, result.getD());
            m_description.setText(txt);

            StringBuilder sb = new StringBuilder(128);
            sb.append(result.getValues()[0]);
            for (int i = 1; i < result.getValues().length; ++i)
                sb.append(", ").append(result.getValues()[i]);
            m_content.setText(sb.toString());
            m_content.setVisibility(View.VISIBLE);
        }
        void setOnClickListener(View.OnClickListener listener)
        {
            m_root.setOnClickListener(listener);
        }
    }
}
