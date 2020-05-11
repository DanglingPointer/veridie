package com.vasilyev.veridie.utils;

import android.content.Context;
import android.os.Parcel;
import android.text.TextUtils;

import com.vasilyev.veridie.R;

import java.util.Arrays;

public class Cast
{
    public static final String[] DICE_TYPES = new String[]{
            "D2",
            "D4",
            "D6",
            "D8",
            "D10",
            "D12",
            "D16",
            "D20",
            "D100"
    };

    public static class Request
    {
        private final String m_d;
        private final int m_count;
        private final int m_successFrom;

        public Request(String diceType, int count, int successFrom, Context ctx) throws IllegalArgumentException
        {
            if (!Arrays.asList(DICE_TYPES).contains(diceType))
                throw new IllegalArgumentException(ctx.getString(R.string.ex_invalid_dice_type));
            if (count < 1 || count > 70)
                throw new IllegalArgumentException(ctx.getString(R.string.ex_invalid_count));
            if (successFrom > Integer.parseInt(diceType.substring(1)))
                throw new IllegalArgumentException(ctx.getString(R.string.ex_invalid_threshold));
            m_d = diceType;
            m_count = count;
            m_successFrom = successFrom;
        }
        public Request(Parcel source)
        {
            m_d = source.readString();
            m_count = source.readInt();
            m_successFrom = source.readInt();
        }
        public void toParcel(Parcel dest)
        {
            dest.writeString(m_d);
            dest.writeInt(m_count);
            dest.writeInt(m_successFrom);
        }
        public String getD()
        {
            return m_d;
        }
        public int getCount()
        {
            return m_count;
        }
        public boolean hasThreshold()
        {
            return m_successFrom != 0;
        }
        public Integer getSuccessFrom()
        {
            return m_successFrom == 0 ? null : m_successFrom;
        }
    }

    public static class Result
    {
        private final String m_d;
        private final int m_successCount;
        private final int[] m_values;

        public Result(String diceType, String values, int successCount) throws IllegalArgumentException
        {
            m_d = diceType;
            m_successCount = successCount;

            String[] strValues = TextUtils.split(values, ";");
            m_values = new int[strValues.length - 1]; // last one will be empty
            for (int i = 0; i < m_values.length; ++i) {
                m_values[i] = Integer.parseInt(strValues[i]);
            }
        }
        public Result(Parcel source)
        {
            m_d = source.readString();
            m_successCount = source.readInt();
            m_values = source.createIntArray();
        }
        public void toParcel(Parcel dest)
        {
            dest.writeString(m_d);
            dest.writeInt(m_successCount);
            dest.writeIntArray(m_values);
        }
        public String getD()
        {
            return m_d;
        }
        public int[] getValues()
        {
            return m_values;
        }
        public boolean hasSuccessCount()
        {
            return m_successCount != -1;
        }
        public Integer getSuccessCount()
        {
            return m_successCount == -1 ? null : m_successCount;
        }
    }
}
