package com.monitorjbl.xlsx.impl;

import static com.monitorjbl.xlsx.XmlUtils.document;
import static com.monitorjbl.xlsx.XmlUtils.searchForNodeList;
import static java.util.Arrays.asList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStringsTable;
import org.apache.poi.xssf.model.StylesTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.monitorjbl.xlsx.StreamingReader.Builder;
import com.monitorjbl.xlsx.exceptions.OpenException;
import com.monitorjbl.xlsx.exceptions.ReadException;

public class StreamingWorkbookReader implements Iterable<Sheet>, AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(StreamingWorkbookReader.class);

  private final List<StreamingSheet> sheets;
  private final List<Map<String, String>> sheetProperties = new ArrayList<>();
  private final Builder builder;
  private File tmp;
  private OPCPackage pkg;

  /**
   * This constructor exists only so the StreamingReader can instantiate
   * a StreamingWorkbook using its own reader implementation. Do not use
   * going forward.
   *
   * @param pkg     The POI package that should be closed when this workbook is closed
   * @param reader  A single streaming reader instance
   * @param builder The builder containing all options
   */
  @Deprecated
  public StreamingWorkbookReader(OPCPackage pkg, StreamingSheetReader reader, Builder builder) {
    this.pkg = pkg;
    this.sheets = asList(new StreamingSheet(null, reader));
    this.builder = builder;
  }

  public StreamingWorkbookReader(Builder builder) {
    this.sheets = new ArrayList<>();
    this.builder = builder;
  }

  public StreamingSheetReader first() {
    return sheets.get(0).getReader();
  }

  public void init(InputStream is) {
    File f = null;
    try {
      f = writeInputStreamToFile(is, builder.getBufferSize());
      log.debug("Created temp file [" + f.getAbsolutePath() + "]");

      init(f);
      tmp = f;
    } catch(IOException e) {
      throw new ReadException("Unable to read input stream", e);
    } catch(RuntimeException e) {
      f.delete();
      throw e;
    }
  }

  public void init(File f) {
    try {
      pkg = OPCPackage.open(f.getAbsolutePath());

      XSSFReader reader = new XSSFReader(pkg);
      SharedStringsTable sst = reader.getSharedStringsTable();
      StylesTable styles = reader.getStylesTable();

      loadSheets(reader, sst, styles, builder.getRowCacheSize());
    } catch(IOException e) {
      throw new OpenException("Failed to open file", e);
    } catch(OpenXML4JException | XMLStreamException e) {
      throw new ReadException("Unable to read workbook", e);
    }
  }

  void loadSheets(XSSFReader reader, SharedStringsTable sst, StylesTable stylesTable, int rowCacheSize) throws IOException, InvalidFormatException,
      XMLStreamException {
    lookupSheetNames(reader);
    Iterator<InputStream> iter = reader.getSheetsData();
    int i = 0;
    while(iter.hasNext()) {
      XMLEventReader parser = XMLInputFactory.newInstance().createXMLEventReader(iter.next());
      sheets.add(new StreamingSheet(sheetProperties.get(i++).get("name"), new StreamingSheetReader(sst, stylesTable, parser, rowCacheSize)));
    }
  }

  void lookupSheetNames(XSSFReader reader) throws IOException, InvalidFormatException {
    sheetProperties.clear();
    NodeList nl = searchForNodeList(document(reader.getWorkbookData()), "/workbook/sheets/sheet");
    for(int i = 0; i < nl.getLength(); i++) {
      Map<String, String> props = new HashMap<>();
      props.put("name", nl.item(i).getAttributes().getNamedItem("name").getTextContent());

      Node state = nl.item(i).getAttributes().getNamedItem("state");
      props.put("state", state == null ? "visible" : state.getTextContent());
      sheetProperties.add(props);
    }
  }

  List<? extends Sheet> getSheets() {
    return sheets;
  }

  public List<Map<String, String>> getSheetProperties() {
    return sheetProperties;
  }

  @Override
  public Iterator<Sheet> iterator() {
    return new StreamingSheetIterator(sheets.iterator());
  }

  @Override
  public void close() {
    try {
      for(StreamingSheet sheet : sheets) {
        sheet.getReader().close();
      }
      pkg.revert();
    } finally {
      if(tmp != null) {
        log.debug("Deleting tmp file [" + tmp.getAbsolutePath() + "]");
        tmp.delete();
      }
    }
  }

  static File writeInputStreamToFile(InputStream is, int bufferSize) throws IOException {
    File f = Files.createTempFile("tmp-", ".xlsx").toFile();
    try(FileOutputStream fos = new FileOutputStream(f)) {
      int read;
      byte[] bytes = new byte[bufferSize];
      while((read = is.read(bytes)) != -1) {
        fos.write(bytes, 0, read);
      }
      is.close();
      fos.close();
      return f;
    }
  }

  static class StreamingSheetIterator implements Iterator<Sheet> {
    private final Iterator<StreamingSheet> iterator;

    public StreamingSheetIterator(Iterator<StreamingSheet> iterator) {
      this.iterator = iterator;
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public Sheet next() {
      return iterator.next();
    }

    @Override
    public void remove() {
      throw new RuntimeException("NotSupported");
    }
  }
}
