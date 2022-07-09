package se.alipsa.gride.chart;

import tech.tablesaw.api.ColumnType;
import tech.tablesaw.api.Table;


public class AreaChart extends Chart {


  public static AreaChart create(Table data) {
    if (data.columnCount() != 2) {
      throw new IllegalArgumentException("Table " + data.name() + " does not contain 2 columns.");
    }
    AreaChart chart = new AreaChart();
    chart.title = data.name();
    chart.series = new Table[] {data};
    return chart;
  }

  public static AreaChart create(String title, Table... series) {
    validateSeries(series);
    AreaChart chart = new AreaChart();
    chart.title = title;
    chart.series = series;
    return chart;
  }

  private static void validateSeries(Table[] series) {
    int idx = 0;
    if (series == null || series.length == 0) {
      throw new IllegalArgumentException("The series contains no data");
    }

    Table firstTable = series[0];
    ColumnType firstColumn = firstTable.typeArray()[0];
    ColumnType secondColumn = firstTable.typeArray()[1];
    if (firstTable.columnCount() != 2) {
      throw new IllegalArgumentException("Table " + idx + "(" + firstTable.name() + ") does not contain 2 columns.");
    }

    for (var table : series) {
      if (idx == 0) {
        idx++;
        continue;
      }
      if (table.columnCount() != 2) {
        throw new IllegalArgumentException("Table " + idx + "(" + table.name() + ") does not contain 2 columns.");
      }
      ColumnType col0Type = table.typeArray()[0];
      ColumnType col1Type = table.typeArray()[1];
      if (DataType.differs(firstColumn, col0Type)) {
        throw new IllegalArgumentException("Column mismatch in series " + idx + "(" + table.name()
            + "). First series has type " + firstColumn.name() + " in the first column but this series has " + col0Type);
      }
      if (DataType.differs(secondColumn, col1Type)) {
        throw new IllegalArgumentException("Column mismatch in series " + idx + "(" + table.name()
            + "). First series has type " + secondColumn.name() + " in the second column but this series has " + col1Type);
      }
      idx++;
    }
  }
}
