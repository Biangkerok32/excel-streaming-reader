package com.thundermoose.xlsx;

import com.thundermoose.xlsx.exceptions.OpenException;
import com.thundermoose.xlsx.exceptions.ReadException;
import com.thundermoose.xlsx.impl.StreamingCell;
import com.thundermoose.xlsx.impl.StreamingRow;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStringsTable;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Streaming Excel workbook implementation. Most advanced features of POI are not supported.
 * Use this only if your application can handle iterating through an entire workbook, row by
 * row.
 */
public class StreamingReader implements Iterable<Row> {
  private static final Logger log = LoggerFactory.getLogger(StreamingReader.class);

  private SharedStringsTable sst;
  private XMLEventReader parser;
  private String lastContents;
  private boolean nextIsString;

  private int rowCacheSize;
  private List<Row> rowCache = new ArrayList<>();
  private Iterator<Row> rowCacheIterator;
  private StreamingRow currentRow;
  private StreamingCell currentCell;

  private File tmp;

  private StreamingReader(SharedStringsTable sst, XMLEventReader parser, int rowCacheSize) {
    this.sst = sst;
    this.parser = parser;
    this.rowCacheSize = rowCacheSize;
  }

  /**
   * Read through a number of rows equal to the rowCacheSize field or until there is no more data to read
   *
   * @return
   */
  private boolean getRow() {
    try {
      int iters = 0;
      rowCache = new ArrayList<>();
      while (rowCache.size() < rowCacheSize && parser.hasNext()) {
        handleEvent(parser.nextEvent());
        iters++;
      }
      rowCache.add(currentRow);
      rowCacheIterator = rowCache.iterator();
      return iters > 0;
    } catch (XMLStreamException | SAXException e) {
      log.debug("End of stream");
    }
    return false;
  }

  /**
   * Handles a SAX event.
   *
   * @param event
   * @throws SAXException
   */
  private void handleEvent(XMLEvent event) throws SAXException {
    if (event.getEventType() == XMLStreamConstants.CHARACTERS) {
      Characters c = event.asCharacters();
      lastContents += c.getData();
    } else if (event.getEventType() == XMLStreamConstants.START_ELEMENT) {
      StartElement startElement = event.asStartElement();
      if (startElement.getName().getLocalPart().equals("c")) {
        Attribute ref = startElement.getAttributeByName(new QName("r"));

        String[] coord = ref.getValue().split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        StreamingCell cc = new StreamingCell(CellReference.convertColStringToIndex(coord[0]), Integer.parseInt(coord[1]) - 1);

        if (currentCell == null || currentCell.getRowIndex() != cc.getRowIndex()) {
          if (currentRow != null) {
            rowCache.add(currentRow);
          }
          currentRow = new StreamingRow(cc.getRowIndex());
        }
        currentCell = cc;

        Attribute type = startElement.getAttributeByName(new QName("t"));
        String cellType = type == null ? null : type.getValue();
        nextIsString = cellType != null && cellType.equals("s");
      }
      // Clear contents cache
      lastContents = "";
    } else if (event.getEventType() == XMLStreamConstants.END_ELEMENT) {
      EndElement endElement = event.asEndElement();
      if (nextIsString) {
        int idx = Integer.parseInt(lastContents);
        lastContents = new XSSFRichTextString(sst.getEntryAt(idx)).toString();
        nextIsString = false;
      }

      if (endElement.getName().getLocalPart().equals("v")) {
        currentCell.setContents(lastContents);
        currentRow.getCellList().add(currentCell);
      }

    }
  }

  @Override
  public Iterator<Row> iterator() {
    return new StreamingIterator();
  }

  /**
   * Creates a new instance of StreamingReader from a file.
   *
   * @param f
   * @param rowCacheSize
   * @return
   */
  public static StreamingReader createReader(File f, int sheetIndex, int rowCacheSize) {
    try {
      OPCPackage pkg = OPCPackage.open(f);
      XSSFReader reader = new XSSFReader(pkg);
      SharedStringsTable sst = reader.getSharedStringsTable();

      Iterator<InputStream> iter = reader.getSheetsData();
      InputStream sheet = null;
      int index = 0;
      while (iter.hasNext()) {
        InputStream is = iter.next();
        if (index++ == sheetIndex) {
          sheet = is;
          log.debug("Found sheet at index [" + sheetIndex + "]");
          break;
        }
      }

      if (sheet == null) {
        throw new RuntimeException("Unable to find sheet at index [" + sheetIndex + "]");
      }

      XMLEventReader parser = XMLInputFactory.newInstance().createXMLEventReader(sheet);
      return new StreamingReader(sst, parser, rowCacheSize);
    } catch (IOException e) {
      throw new OpenException("Failed to open file", e);
    } catch (OpenXML4JException | XMLStreamException e) {
      throw new ReadException("Unable to read workbook", e);
    }
  }

  /**
   * Creates a new instance of StreamingReader from an InputStream. WARNING: this class will attempt to remove
   * the file once all data has been read from it, but does not guarantee its deletion. It is not recommended to
   * use this in production.
   *
   * @param is
   * @param rowCacheSize
   * @return
   */
  public static StreamingReader createReader(InputStream is, int sheetIndex, int rowCacheSize) {
    File f = null;
    try {
      f = writeInputStreamToFile(is);
      log.debug("Created temp file [" + f.getAbsolutePath() + "]");

      StreamingReader r = createReader(f, sheetIndex, rowCacheSize);
      r.tmp = f;
      return r;
    } catch (IOException e) {
      throw new ReadException("Unable to read input stream", e);
    } finally {
      if (f != null && !f.delete()) {
        System.err.println("Could not delete file " + f.getAbsolutePath());
      }
    }
  }

  static File writeInputStreamToFile(InputStream is) throws IOException {
    File f = Files.createTempFile("tmp-", ".xls").toFile();
    try (FileOutputStream fos = new FileOutputStream(f)) {
      int read;
      byte[] bytes = new byte[1024];
      while ((read = is.read(bytes)) != -1) {
        fos.write(bytes, 0, read);
      }
      is.close();
      fos.close();
      return f;
    }
  }

  class StreamingIterator implements Iterator<Row> {
    @Override
    public boolean hasNext() {
      boolean has = (rowCacheIterator != null && rowCacheIterator.hasNext()) || getRow();

      //delete temporary file (if workbook was from inputstream)
      if (tmp != null && !has) {
        log.debug("attempting to delete temp file");
        tmp.delete();
      }

      return has;
    }

    @Override
    public Row next() {
      return rowCacheIterator.next();
    }

    @Override
    public void remove() {
      throw new RuntimeException("NotSupported");
    }
  }

}