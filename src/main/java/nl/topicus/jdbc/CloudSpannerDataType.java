package nl.topicus.jdbc;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Type;
import com.google.cloud.spanner.Type.Code;
import nl.topicus.jdbc.util.CloudSpannerConversionUtil;

public enum CloudSpannerDataType {
  BOOL {
    @Override
    public int getSqlType() {
      return Types.BOOLEAN;
    }

    @Override
    public Class<Boolean> getJavaClass() {
      return Boolean.class;
    }

    @Override
    public Code getCode() {
      return Code.BOOL;
    }

    @Override
    public List<Boolean> getArrayElements(ResultSet rs, int columnIndex) {
      return rs.getBooleanList(columnIndex);
    }

    @Override
    public Type getGoogleType() {
      return Type.bool();
    }
  },
  BYTES {
    @Override
    public int getSqlType() {
      return Types.BINARY;
    }

    @Override
    public Class<byte[]> getJavaClass() {
      return byte[].class;
    }

    @Override
    public Code getCode() {
      return Code.BYTES;
    }

    @Override
    public List<byte[]> getArrayElements(ResultSet rs, int columnIndex) {
      return CloudSpannerConversionUtil.toJavaByteArrays(rs.getBytesList(columnIndex));
    }

    @Override
    public Type getGoogleType() {
      return Type.bytes();
    }
  },
  DATE {
    @Override
    public int getSqlType() {
      return Types.DATE;
    }

    @Override
    public Class<Date> getJavaClass() {
      return Date.class;
    }

    @Override
    public Code getCode() {
      return Code.DATE;
    }

    @Override
    public List<Date> getArrayElements(ResultSet rs, int columnIndex) {
      return CloudSpannerConversionUtil.toJavaDates(rs.getDateList(columnIndex));
    }

    @Override
    public Type getGoogleType() {
      return Type.date();
    }
  },
  FLOAT64 {
    private Set<Class<?>> classes =
        new HashSet<>(Arrays.asList(BigDecimal.class, Float.class, Double.class));

    @Override
    public int getSqlType() {
      return Types.DOUBLE;
    }

    @Override
    public Class<Double> getJavaClass() {
      return Double.class;
    }

    @Override
    public Set<Class<?>> getSupportedJavaClasses() {
      return classes;
    }

    @Override
    public Code getCode() {
      return Code.FLOAT64;
    }

    @Override
    public List<Double> getArrayElements(ResultSet rs, int columnIndex) {
      return rs.getDoubleList(columnIndex);
    }

    @Override
    public Type getGoogleType() {
      return Type.float64();
    }
  },
  INT64 {
    private Set<Class<?>> classes =
        new HashSet<>(Arrays.asList(Byte.class, Integer.class, Long.class));

    @Override
    public int getSqlType() {
      return Types.BIGINT;
    }

    @Override
    public Class<Long> getJavaClass() {
      return Long.class;
    }

    @Override
    public Set<Class<?>> getSupportedJavaClasses() {
      return classes;
    }

    @Override
    public Code getCode() {
      return Code.INT64;
    }

    @Override
    public List<Long> getArrayElements(ResultSet rs, int columnIndex) {
      return rs.getLongList(columnIndex);
    }

    @Override
    public Type getGoogleType() {
      return Type.int64();
    }
  },
  STRING {
    @Override
    public int getSqlType() {
      return Types.NVARCHAR;
    }

    @Override
    public Class<String> getJavaClass() {
      return String.class;
    }

    @Override
    public Code getCode() {
      return Code.STRING;
    }

    @Override
    public List<String> getArrayElements(ResultSet rs, int columnIndex) {
      return rs.getStringList(columnIndex);
    }

    @Override
    public Type getGoogleType() {
      return Type.string();
    }
  },
  TIMESTAMP {
    @Override
    public int getSqlType() {
      return Types.TIMESTAMP;
    }

    @Override
    public Class<Timestamp> getJavaClass() {
      return Timestamp.class;
    }

    @Override
    public Code getCode() {
      return Code.TIMESTAMP;
    }

    @Override
    public List<Timestamp> getArrayElements(ResultSet rs, int columnIndex) {
      return CloudSpannerConversionUtil.toJavaTimestamps(rs.getTimestampList(columnIndex));
    }

    @Override
    public Type getGoogleType() {
      return Type.timestamp();
    }
  };

  public abstract int getSqlType();

  public abstract Code getCode();

  public abstract Type getGoogleType();

  /**
   * 
   * @param rs the result set to look up the elements
   * @param columnIndex zero based column index
   * @return The corresponding array elements of the type in the given result set
   */
  public abstract List<?> getArrayElements(ResultSet rs, int columnIndex);

  public String getTypeName() {
    return name();
  }

  public abstract Class<?> getJavaClass();

  public Set<Class<?>> getSupportedJavaClasses() {
    return Collections.singleton(getJavaClass());
  }

  public static CloudSpannerDataType getType(Class<?> clazz) {
    for (CloudSpannerDataType type : CloudSpannerDataType.values()) {
      if (type.getSupportedJavaClasses().contains(clazz))
        return type;
    }
    return null;
  }

  public static CloudSpannerDataType getType(Code code) {
    for (CloudSpannerDataType type : CloudSpannerDataType.values()) {
      if (type.getCode() == code)
        return type;
    }
    return null;
  }

}
