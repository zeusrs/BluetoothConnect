package iter.bluetoothconnect;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;

import android.graphics.Paint;
import android.location.Location;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.androidplot.Plot;
import com.androidplot.PlotListener;
import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.PanZoom;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.XYGraphWidget;
import com.androidplot.xy.XYPlot;
import com.androidplot.xy.ZoomEstimator;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;

import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.json.JSONException;
import org.json.JSONObject;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * This activity stablish a bluetooth connection with the selected device and show all data in graph
 */
//http://cursoandroidstudio.blogspot.com.es/2015/10/conexion-bluetooth-android-con-arduino.html
public class DataActivity extends AppCompatActivity  implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{

    public static final String FILE_LIST = "file_list";
    private Spinner spinner;
    private TextView tvTemp;
    private TextView tvBat;
    private ValuesFragment dialogFragment;
    private ProgressDialogFragment progressDialogFragment;

    private Handler bluetoothIn;
    private Handler writeToFileHandler;
    private Handler uploadFileToFTPHandler;
    private Handler stablishBluetoothConnectionHandler;

    private final int handlerState = 0;
    private BluetoothAdapter btAdapter = null;
    private BluetoothSocket btSocket = null;
    private StringBuilder recDataString = new StringBuilder();
    private static final UUID BTMODULEUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public static final String FTP_IP = "ftp.iter.es";
    public static final int FTP_PORT = 21;
    public static final String FTP_USER = "jbarrancos";
    public static final String FTP_PASS = "bebacafebe";

    private ToggleButton tgRecord;
    private StringBuilder dataToFile = new StringBuilder();
    /***/
    private XYPlot plot;
    private PanZoom panZoom;
    private int minX;
    private int maxX;

    private ConnectedThread mConnectedThread;
    private HashMap<String, SimpleXYSeries> dataMapped;

    private String startDate;
    private GoogleApiClient mGoogleApiClient;

    private LinearLayout llMenuSlope;
    private Button btSaveData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   //keep screen ON

        btAdapter = BluetoothAdapter.getDefaultAdapter();

        minX = -1;
        maxX = 1;

        dialogFragment = new ValuesFragment();
        progressDialogFragment = new ProgressDialogFragment();
        tgRecord = (ToggleButton)findViewById(R.id.toggleRecording);
        /*Toggle button ON/OFF reading data*/
        tgRecord.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (isChecked){
                Log.v("DataActivity", "Recording...");
                hideOptionMenu();
                plot.setTitle("");
                panZoom.setEnabled(false);
                dataToFile.setLength(0);
                clearAllSeries();   //Inicializa series
                plot.setDomainBoundaries(0,1,BoundaryMode.AUTO);
                //change left drawable
                int imgResource = android.R.drawable.ic_media_pause;
                tgRecord.setCompoundDrawablesWithIntrinsicBounds(imgResource, 0, 0, 0);
                startDate = new SimpleDateFormat("HH:mm:ss_dd-MM-yyyy").format(new Date());

                launchBluetoothReaderThread();
                //plot.clear();   //Limpia grafica
            }else {
                //change left drawable
                int imgResource = android.R.drawable.ic_media_play;
                tgRecord.setCompoundDrawablesWithIntrinsicBounds(imgResource, 0, 0, 0);
                stopBluetoothReader();
                if (mConnectedThread != null)
                    mConnectedThread.interrupt();
                Log.v("DataActivity", "Stop recording");
               // plot.calculateMinMaxVals();
                panZoom.setEnabled(true);
                forceUpdateSlopeText();
                showOptionMenu();
            }
            }
        });

        /*List of items*/
        spinner = (Spinner) findViewById(R.id.spinner);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
               // Log.v("DataActivity", "tap"+position);
                //plot.setTitle(parent.getItemAtPosition(position).toString());
                String text = spinner.getSelectedItem().toString();
                plot.clear();
                addSerieToPlot(text);
                plot.redraw();

                /*Update slope*/
                if (!tgRecord.isChecked())
                    forceUpdateSlopeText();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        plot = (XYPlot) findViewById(R.id.plot);
        tvTemp = (TextView) findViewById(R.id.tvTemp);
        tvBat = (TextView)findViewById(R.id.tvBat);
        initSeries();
       // plot.setDomainBoundaries(0, POINTS_IN_PLOT, BoundaryMode.AUTO);
        plot.setRangeBoundaries(0,1, BoundaryMode.AUTO);
        plot.setDomainBoundaries(0,1, BoundaryMode.AUTO);
        plot.setDomainStepValue(5);
        plot.setLinesPerRangeLabel(5);
        panZoom = PanZoom.attach(plot);
       //panZoom = PanZoom.attach(plot, PanZoom.Pan.HORIZONTAL, PanZoom.Zoom.STRETCH_HORIZONTAL, PanZoom.ZoomLimit.MIN_TICKS);
        panZoom.setZoom(PanZoom.Zoom.STRETCH_HORIZONTAL);
        panZoom.setPan(PanZoom.Pan.HORIZONTAL);
        panZoom.setEnabled(false);
        plot.getOuterLimits().set(0,1, 0, 1000);
        plot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.LEFT).setFormat(new DecimalFormat("#####.##"));
        //plot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.BOTTOM).setFormat(new DecimalFormat("###"));
        //plot.getRegistry().setEstimator(new ZoomEstimator());

        //plot.setRangeBottomMin(-10);
        /*plot.setRangeTopMax(400);
        plot.setRangeTopMin(1);*/
        //plot.setRangeBottomMax(0);
        //plot.setDomainStep(StepMode.INCREMENT_BY_VAL, 20);

        plot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.LEFT).setFormat(new DecimalFormat("###.#"));
      //  plot.setTitle(spinner.getSelectedItem().toString());
        plot.addListener(new PlotListener() {
            @Override
            public void onBeforeDraw(Plot source, Canvas canvas) {
               // Log.v("BeforeDraw", "OK");
            }

            @Override
            public void onAfterDraw(Plot source, Canvas canvas) {
                //plot.calculateMinMaxVals();
                int domainMin = (int)(Math.ceil(plot.getBounds().getMinX().doubleValue()));
                int domainMax = (int)(Math.floor(plot.getBounds().getMaxX().doubleValue()));
                if ((domainMin != minX) || (domainMax != maxX)){
                    minX = domainMin;
                    maxX = domainMax;
                    if (!tgRecord.isChecked()){
                        double slope = calculateSlope(spinner.getSelectedItem().toString(), minX, maxX);
                        double correlation = calculatePearsonCorrelation(spinner.getSelectedItem().toString(), minX, maxX);
                        plot.setTitle(String.format( "Pte: %.5f \n Crl: %.5f", slope, correlation));
                    }
                }
            }
        });

        /*Create a instance of Google Api Client*/
        if (mGoogleApiClient == null){
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(LocationServices.API)
                    .build();
        }

        llMenuSlope = (LinearLayout)findViewById(R.id.llMenuSlope);
        btSaveData = (Button) findViewById(R.id.btOptions);
        btSaveData.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
            showSaveDialog();
            }
        });

        /**** Handlers ****/
        bluetoothIn = new Handler(){
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                if (msg.what == handlerState){
                    String readMessage = (String)msg.obj;
                    recDataString.append(readMessage);
                    int endOfLineIndex = recDataString.indexOf("\n");

                    if (endOfLineIndex > 0) {
                        //String dataInPrint = recDataString.substring(0, endOfLineIndex);
                        //Quitamos corchetes de inicio([) y final (]/r/n)
                        int initOfLineIndex =  recDataString.indexOf("[");
                        String dataInPrint = recDataString.substring(initOfLineIndex + 1, endOfLineIndex - 2);
                        Log.v("DataInPrint_",dataInPrint);
                        //String[] values = dataInPrint.split(",");

                        // if toggle button is checked, then add data parsed to series for showing in Plot
                        if (tgRecord.isChecked()) {

                            customParser(",", ":", dataInPrint);
                            /*****************************/
                            //Insert values into map
                            /*for (int i = 0; i < values.length; i++) {
                                String[] items = values[i].split(":");
                                if (!items[0].contentEquals("LIC")) {
                                    SimpleXYSeries serie = dataMapped.get(items[0]);
                                    if (serie != null) {
                                        if (!items[1].contains("NAN")){
                                            try {
                                                Number n = Double.parseDouble(items[1]);
                                                if (n != null )
                                                    serie.addLast(null, n);
                                            }catch (NumberFormatException e){
                                                serie.addLast(null, 0);
                                            }catch (NullPointerException e){
                                                serie.addLast(null, 0);
                                            }
                                        }else
                                            serie.addLast(null, 0);
                                    }
                                } else {    //Parse "LIC" xml
                                    String wellFormedXML = "<xml>" + items[1] + "</xml>";
                                    ArrayList<String> xmlItems = parseXml(wellFormedXML);
                                    if (xmlItems != null){
                                        for (int j = 0; j < xmlItems.size(); j++){
                                            String[] xmlItem = xmlItems.get(j).split(":");
                                            SimpleXYSeries serie = dataMapped.get(xmlItem[0].toUpperCase());
                                            if (serie != null) {
                                                Number n = Double.parseDouble(xmlItem[1]);
                                                if (n != null ){
                                                    serie.addLast(null, n);
                                                }else{
                                                    serie.addLast(null, 0);
                                                }
                                                //parse LIC <co2>394.76</co2><tem>51.2</tem><pre>101383</pre><h2o>394.76</h2o><bat>12.4</bat>
                                            }
                                            //Show info in dialog if it is shown
                                            if (xmlItem[0].toLowerCase().equals("bat")){
                                                updateBattery(xmlItem[1]);
                                                if ((dialogFragment != null) && (dialogFragment.shown)){
                                                    dialogFragment.updateTV1("Bateria \n"+xmlItem[1]+"%");
                                                }
                                            }
                                            if (xmlItem[0].toLowerCase().equals("tem")){
                                                updateTemp(xmlItem[1]);
                                                if ((dialogFragment != null) && (dialogFragment.shown)){
                                                    dialogFragment.updateTV2("Temperatura \n"+xmlItem[1]+"º");
                                                }
                                            }
                                            if (xmlItem[0].toLowerCase().equals("pre")){
                                                if ((dialogFragment != null) && (dialogFragment.shown)){
                                                    dialogFragment.updateTV3("Presion \n"+xmlItem[1]+"-");
                                                }
                                            }
                                            if (xmlItem[0].toLowerCase().equals("flu")){
                                                if ((dialogFragment != null) && (dialogFragment.shown)){
                                                    dialogFragment.updateTV3("Flujo \n"+xmlItem[1]+"-");
                                                }
                                            }
                                        }
                                    }
                                }
                                plot.redraw();
                                dataToFile.append(dataInPrint + "\n");
                                Log.v("DataActivity", dataInPrint);
                            }*/
                            /*Fixed bug: Update zoomable range to current max X (visible)*/
                            Number n = maxX + 1;
                            plot.getOuterLimits().set(0, n, 0, 1000);
                            plot.redraw();
                            dataToFile.append(dataInPrint + " - " + new SimpleDateFormat("HH:mm:ss").format(new Date())+ "\n");
                            updateInfoWidget();
                            Log.v("DataActivity", dataInPrint);
                            /***/
                            recDataString.setLength(0); //clear buffer
                        }
                    }
                }
            }
        };

        /*handle FTP upload*/
        uploadFileToFTPHandler = new Handler(){
            @Override
            public void handleMessage(Message msg) {
            super.handleMessage(msg);
            String fileName =     (String)msg.obj;
            if (msg.what == 1)
                Toast.makeText(getApplicationContext(),"Fichero subido con exito "+fileName, Toast.LENGTH_LONG).show();
            else{
                Toast.makeText(getApplicationContext(),"Error subiendo el fichero "+ fileName +" al FTP!", Toast.LENGTH_LONG).show();
                updateFileList(fileName);
            }
                }
        };

        /*handle file writer*/
        writeToFileHandler = new Handler(){
            @Override
            public void handleMessage(Message msg) {
            super.handleMessage(msg);
            final String currentPath = (String)msg.obj;
            if (msg.what == 1){
                UploadFileToFTPThread uploadFileToFTPThread = new UploadFileToFTPThread(currentPath);
                uploadFileToFTPThread.start();
                hideOptionMenu();
            }else{
                Toast.makeText(getApplicationContext(),"Error generando el fichero "+ (String)msg.obj, Toast.LENGTH_LONG).show();
            }
            }
        };

        /*Handle stablish bluetooth connection*/
       stablishBluetoothConnectionHandler = new Handler(){
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                progressDialogFragment.dismiss();
                if (msg.what == 1){
                    if ((btSocket != null) && (btSocket.isConnected())){
                        mConnectedThread = new ConnectedThread(btSocket);
                        mConnectedThread.write("1");
                        mConnectedThread.start();
                    }
                }
            }
        };
    }

    /*Start bluetooth connection */
   private void launchBluetoothReaderThread(){
       Intent intent = getIntent();
       String address = intent.getStringExtra(MainActivity.EXTRA_DEVICE_ADDRESS);   //get current MAC
       StablishBluetoothConnection stablishBluetoothConnection = new StablishBluetoothConnection(address);
       stablishBluetoothConnection.start();
       /*  Log.v("DataActivity", "Starting bluetooth");
        if ((btSocket == null) || (!btSocket.isConnected())){
            BluetoothDevice device = btAdapter.getRemoteDevice(address);
            try {
                btSocket = device.createRfcommSocketToServiceRecord(BTMODULEUUID);
            }catch (IOException e){
                Toast.makeText(getBaseContext(), "La creacción del Socket fallo", Toast.LENGTH_LONG).show();
                finish();
            }
            try {
                btSocket.connect();
            }catch (IOException e){
                try {
                    btSocket.close();
                }catch (IOException e2){

                }
                Toast.makeText(getBaseContext(), "No se pudo establecer conexion", Toast.LENGTH_LONG).show();
                finish();
            }
            if (btSocket.isConnected()){
                mConnectedThread = new ConnectedThread(btSocket);
                mConnectedThread.write("1");
                mConnectedThread.start();
            }
            Log.v("DataActivity", "Finishing bluetooth");
          //  loadingdialog.dismiss();
        }*/
    }

    /*Stop bluetooth reader*/
    private void stopBluetoothReader(){
        if (mConnectedThread != null)
            mConnectedThread.write("0");    //Turn off led
        if ((btSocket != null) && (btSocket.isConnected())){
            try {
                btSocket.close();   //Close bluetooth socket
            }catch (IOException e){

            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mConnectedThread != null) {
            mConnectedThread.write("0");
            mConnectedThread.interrupt();
        }
        stopBluetoothReader();
    }

    @Override
    protected void onStart() {
        mGoogleApiClient.connect();
        super.onStart();
    }

    @Override
    protected void onStop() {
        mGoogleApiClient.disconnect();
        super.onStop();
    }

    /*Launch openWith activity*/
   /* private void openWith(String path){
        if (path != null){
            File f= new File(path);
            if (f != null){
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.fromFile(f),"text/plain");
                Intent chooser = Intent.createChooser(intent, "Abrir con...");
                startActivity(chooser);
            }
        }
    }*/

    /*Initialize all series*/
    private void initSeries(){
        dataMapped = new HashMap<String, SimpleXYSeries>();
        String[] spinnerItems = getResources().getStringArray(R.array.items_name); //leemos valores de string
        for (int i = 0; i < spinnerItems.length; i++){
            ArrayList<Number> values = new ArrayList<Number>();
            dataMapped.put(spinnerItems[i],new SimpleXYSeries(values, SimpleXYSeries.ArrayFormat.Y_VALS_ONLY, spinnerItems[i]));
        }
    }

    private void initSerie(String serieName){
        if (dataMapped == null){
            dataMapped = new HashMap<String, SimpleXYSeries>();
        }
        ArrayList<Number> values = new ArrayList<Number>();
        dataMapped.put(serieName, new SimpleXYSeries(values, SimpleXYSeries.ArrayFormat.Y_VALS_ONLY, serieName));
    }

    private void updateSpinner(String name){
        SpinnerAdapter spinnerAdapter = spinner.getAdapter();
        int size = spinnerAdapter.getCount();
        ArrayList<String> listSpinner = new ArrayList<>();
        for (int i = 0; i < size; i++){
            listSpinner.add(spinnerAdapter.getItem(i).toString());
        }
        listSpinner.add(name);
        ArrayAdapter<String> spinnerAdapter2 = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, listSpinner);
        spinnerAdapter2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter2);
        spinnerAdapter2.notifyDataSetChanged();
    }

    /*Clear all series*/
    private void clearAllSeries(){

        if (dataMapped != null){
            for (SimpleXYSeries serie : dataMapped.values()){
                //remove items in plot
                while (serie.size() > 0) {
                    serie.removeLast();
                }
            }
        }
    }

    /**
     * Add serie called serieName to plot
     * @param serieName Name of the serie
     */
    private void addSerieToPlot(String serieName){

        SimpleXYSeries serie = dataMapped.get(serieName);
        if (serie != null){
            //LineAndPointFormatter series1Format = new LineAndPointFormatter(Color.RED, Color.RED, null, null);
            //LineAndPointFormatter series1Format = new LineAndPointFormatter(Color.rgb(200, 0, 0), null, null, null);
            LineAndPointFormatter series1Format = new LineAndPointFormatter(Color.rgb(150, 0, 0), null, Color.argb(125, 100, 0, 0), null);
            series1Format.getLinePaint().setStrokeJoin(Paint.Join.ROUND);
            series1Format.getLinePaint().setStrokeWidth(7);
            plot.addSeries(serie, series1Format);
        }
    }

    private void forceUpdateSlopeText(){
        /*Update slope*/
       // plot.calculateMinMaxVals();
        minX = (int)(Math.ceil(plot.getBounds().getMinX().doubleValue()));
        maxX = (int)(Math.floor(plot.getBounds().getMaxX().doubleValue()));
        double slope = calculateSlope(spinner.getSelectedItem().toString(), minX, maxX);
        plot.setTitle(String.format( "Pte: %.5f", slope ));
    }

    /**
     * Calculate slope
     * @param serieName String
     * @param domainMin int min value X axis
     * @param domainMax int max value X axis
     * @return double slope value
     */
    private double calculateSlope(String serieName, int domainMin, int domainMax){

        double result = 0.0;
        SimpleXYSeries serie = dataMapped.get(serieName);
        if ((serie.size() > 1) && (domainMin < domainMax)){
            int size = domainMax;
            if (domainMax > serie.size())
                size = serie.size();
            SimpleRegression simpleRegression = new SimpleRegression(true);
            for (int i = domainMin; i < size; i++){
                //TODO revise range
                simpleRegression.addData(serie.getX(i).doubleValue(), serie.getY(i).doubleValue());
            }
            result = simpleRegression.getSlope();

            Log.v("Slope",""+domainMin + " , "+domainMax + "  Slope: "+ result);
        }
        //Toast.makeText(DataActivity.this, "Slope range"+domainMin + " - "+domainMax ,Toast.LENGTH_LONG).show();
        return result;
    }

    private double calculatePearsonCorrelation(String serieName, int domainMin, int domainMax){
        double result = 0.0;
        SimpleXYSeries serie = dataMapped.get(serieName);
        if ((serie.size() > 1) && (domainMin < domainMax)) {
            int size = domainMax;
            int serieSize =  serie.size();
            if (domainMax > serieSize)
                size = serieSize;
            double[] x = new double[serieSize];
            double[] y = new double[serieSize];
            for (int i = domainMin; i < size; i++){
                x[i] = serie.getX(i).doubleValue();
                y[i] = serie.getY(i).doubleValue();
            }
            result = new PearsonsCorrelation().correlation(x, y);
        }
        return result;
    }

    /**
     *
     */
    private void showOptionMenu(){
        llMenuSlope.setVisibility(View.VISIBLE);
    }

    private void hideOptionMenu(){
        llMenuSlope.setVisibility(View.GONE);
    }

    private boolean isOptionsMenuShowed(){
        boolean result = true;
        if (llMenuSlope.getVisibility() == View.GONE)
            result = false;
        return result;
    }

    /*private void removeSlope(){
        if (slopeSerie != null)
            plot.removeSeries(slopeSerie);
    }

    private void initSlopeSerie(){
        ArrayList<Number> values = new ArrayList<Number>();
        slopeSerie = new SimpleXYSeries(values, SimpleXYSeries.ArrayFormat.Y_VALS_ONLY, "slope");
    }*/
    /**
     * Parse a XML
     * @return ArrayList of items (name:value)
     */
  /*  private ArrayList<String> parseXml(String s){
       // Log.v("Parsed", s);
        XMLReader xmlReader = null;
        try {
            xmlReader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
        } catch (SAXException e) {
            e.printStackTrace();
            return null;
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
            return null;


        }
        // create a SAXXMLHandler
        SaxXMLHandler saxHandler = new SaxXMLHandler();
        // store handler in XMLReader
        xmlReader.setContentHandler(saxHandler);
        // the process starts
        try {

            xmlReader.parse(new InputSource(new StringReader(s)));

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } catch (SAXException e) {
            e.printStackTrace();
            return null;
        }
        return saxHandler.items;
    }*/

  private void updateInfoWidget(){

      Number n;
      SimpleXYSeries serie = dataMapped.get("celltemp");
      if (serie == null){
          n = 0;
      }else{
          if (serie.size() > 0) {
              n = serie.getY(serie.size() - 1);
              if (n == null)
                  n = 0;
          }else
              n = 0;
      updateTemp(n);
      }

      serie = dataMapped.get("ivolt");
      if (serie == null){
          n = 0;
      }else{
          if ( serie.size() > 0){
            n = serie.getY(serie.size() - 1);
            if (n == null)
                n = 0;
          }else
              n = 0;
      }
      updateBattery(n);
  }

    private void showSaveDialog(){
        if (isOptionsMenuShowed()){
            AlertDialog d = new AlertDialog.Builder(DataActivity.this)
                    .setTitle(R.string.app_name)
                    .setIcon(android.R.drawable.ic_menu_save)
                    .setMessage("¿Desea Guardar y Enviar los datos al FTP?")
                    .setPositiveButton("Si", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (dataToFile.length() > 0){
                                WriteToFileThread writeToFileThread = new WriteToFileThread(dataToFile.toString());
                                writeToFileThread.start();
                                dialog.dismiss();
                            }
                        }
                    })
                    .setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    }).create();
            d.show();
        }
    }

    /*Update fields*/
    private void updateTemp(Number n){
        tvTemp.setText(String.format( "%.1f", n.doubleValue())+"º");
    }
    private void updateBattery(Number n){

        tvBat.setText(String.format( "%.1f", n.doubleValue() ) + "%");
    }
    public void showExtraInfo(View v){
        dialogFragment.show(getSupportFragmentManager(), "InfoFragment");
    }

    /**
     * List containing file names
     * @param file String- File path
     */
    private void updateFileList(String file){
        SharedPreferences sharedPref = getSharedPreferences("Configs",Context.MODE_PRIVATE);
        Set<String> set = sharedPref.getStringSet(FILE_LIST, new HashSet<String>());
        if (!set.contains(file)){
            set.add(file);
        }
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.remove(FILE_LIST);
        editor.commit();
        editor.putStringSet(FILE_LIST, set);
        editor.apply();
    }

    /**
     * Insert key and value into a global hashMap dataMapped
     * @param key String with
     * @param value String with value
     */
    private void insertIntoMap(String key, String value){
        SimpleXYSeries serie = dataMapped.get(key);
        if (serie == null) {
            initSerie(key);
            serie = dataMapped.get(key);
            updateSpinner(key);
        }
        if ((!value.contains("NAN")) && (value.length() > 0)) {
            try {
                Number n = Double.parseDouble(value);
                if (n != null)
                    serie.addLast(null, n);
            } catch (NumberFormatException e) {
                serie.addLast(null, 0);
            } catch (NullPointerException e) {
                serie.addLast(null, 0);
            }
        } else
            serie.addLast(null, 0);

    }

    //[TAM:25.00,HAM:35.00,DIS:184,ANA:[A00|2.23_A01|1.85_A02|1.70_A03|1.50],LIC:[celltemp|5.1704649e1_cellpres|1.0111982e2_co2|4.1958174e2_co2abs|6.6353826e-2_ivolt|1.2219238e1_raw|3780083.3641255]]
    //initial divider1 = ',' divider2 = ':'
    private void customParser(String divider1, String divider2, String data){

        String[] dataList = data.split(divider1);
        if (dataList != null){
            for (String pair : dataList) {
                String[] key_val = pair.split(divider2);
                if (key_val[1].startsWith("[")){
                    //if start with '[' , remove brackets and parse _ and |
                    if (key_val[1].length() > 2){
                        String valueList = key_val[1].substring(1, key_val[1].length() - 2);
                        customParser("_", "\\|", valueList);
                    }
                }else {
                    //Insert key and value
                    insertIntoMap(key_val[0], key_val[1]);
                }
            }
        }
    }

   /* private void loadJson( JSONObject object){
           // JSONObject object = new JSONObject(json); //Creamos un objeto JSON a partir de la cadena
        Iterator<String> iter = object.keys();
        while (iter.hasNext()) {
            String key = iter.next();
            try {
                Object value = object.get(key);
                if (value instanceof JSONObject){
                    loadJson((JSONObject)value);
                }else {
                    SimpleXYSeries serie = dataMapped.get(key);
                    Double item = (Double)object.getDouble(key);
                    if (serie != null) {
                        if (!item.isNaN()) {
                            try {
                                Number n = (Number)item;
                                if (n != null)
                                    serie.addLast(null, n);
                            } catch (NumberFormatException e) {
                                serie.addLast(null, 0);
                            } catch (NullPointerException e) {
                                serie.addLast(null, 0);
                            }
                        } else
                            serie.addLast(null, 0);
                    }
                }
            } catch (JSONException e) {
                // Something went wrong!
            }
        }
    }*/

    @Override
    public void onConnected(@Nullable Bundle bundle) {

    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    /************************* THREADS *************************/

    /**
     * Thread that write a file to SDCard
     */
    private class WriteToFileThread extends Thread{

        private String data;
        private File file;

        public WriteToFileThread(String _data){

            Location loc = null;
            if ((mGoogleApiClient != null) && (mGoogleApiClient.isConnected())){
                try {
                  loc = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
                }catch (SecurityException e){
                    Log.e("DataActivity","Error getting location");
                }
            }
            data = _data;
            if (loc != null){
                data = startDate + "\nLocation: (" + loc.getLatitude() +  ", " + loc.getLongitude() + ") Error: "+ loc.getAccuracy()+" m\n" + data;
            }else
                data = startDate + "\n"+ data;

            String path = Environment.getExternalStorageDirectory() + File.separator  + "stationData";
            // Create the folder.
            File folder = new File(path);
            folder.mkdirs();
            // Create the file.
            //String date = new SimpleDateFormat("HH-mm-ss_dd-MM-yyyy").format(new Date());
            String fileName = startDate.replace(":","-");
            file = new File(folder, "data_"+ fileName+".txt");
            //TODO adjuntar temperatura y demas valores
        }

        @Override
        public void run(){
            // Save your stream, don't forget to flush() it before closing it.
            int status = 0;
            try{
                FileWriter writer = new FileWriter(file);
                writer.append(data);
                writer.flush();
                writer.close();
                Log.d("DataActivity","File saved to "+file.getAbsolutePath());
                //Log.v("DataActivity", data);
                status = 1;
            }catch (IOException e){
                Log.e("Exception", "File write failed: " + e.toString());
            }
            Message msg = new Message();
            msg.what = status;
            msg.obj = file.getAbsolutePath();
            Log.d("File", file.getAbsolutePath());
            if (writeToFileHandler != null)
                writeToFileHandler.sendMessage(msg);
        }
    }

    /**
     * Thread that upload a file to FTP
     */
    private class UploadFileToFTPThread extends Thread{

        private String path;
        public UploadFileToFTPThread(String _path){
            path = _path;
        }

        @Override
        public void run() {
            FTPClient con = null;
            boolean result = false;
            File f = new File(path);
            if (f.exists()){
                try {
                    con = new FTPClient();
                    con.connect(FTP_IP,FTP_PORT);
                    if (con.login(FTP_USER,FTP_PASS)){
                        con.enterLocalPassiveMode();
                        con.setFileType(FTP.BINARY_FILE_TYPE);
                        BufferedInputStream buffIn=null;
                        buffIn=new BufferedInputStream(new FileInputStream(f));
                       // FileInputStream in = new FileInputStream(f);
                        con.changeWorkingDirectory("/appflux");
                        result = con.storeFile(f.getName(),buffIn);
                        buffIn.close();
                        if (result)
                            Log.v("ftp", "Upload success");
                        con.logout();
                        con.disconnect();
                    }else
                        Log.e("ftp", "login error");
                }catch (IOException e){
                    e.getCause().printStackTrace();
                    Log.e("ftp", "Upload fails!!");
                }
            }else
                Log.d("ftp","File not exists");
            Message msg = new Message();
            if (result)
                msg.what = 1;
            else
                msg.what = 0;
            msg.obj = f.getName();
            if (uploadFileToFTPHandler != null)
                uploadFileToFTPHandler.sendMessage(msg);
        }
    }

    /**
     * Thread that stablish a bluetooth connection with a socket
     */
    private class StablishBluetoothConnection extends Thread{

        private String address;
        public StablishBluetoothConnection(String _address) {
            address = _address;
            progressDialogFragment.show(getSupportFragmentManager(), "progressDialog");
            if ((btSocket == null) || (!btSocket.isConnected())) {
                BluetoothDevice device = btAdapter.getRemoteDevice(address);
                try {
                    btSocket = device.createRfcommSocketToServiceRecord(BTMODULEUUID);
                } catch (IOException e) {
                    Toast.makeText(getApplicationContext(), "La creacción del Socket fallo", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        }

        @Override
        public void run() {
            boolean result = false;
            if ((btSocket != null) || (!btSocket.isConnected())){
                try {
                    btSocket.connect();
                    result = true;
                }catch (IOException e){
                    try {
                        btSocket.close();
                    }catch (IOException e2){

                    }
                    Toast.makeText(getApplicationContext(), "No se pudo establecer conexion", Toast.LENGTH_LONG).show();
                    //
                    result = false;
                }
                //Log.v("DataActivity", "Finishing bluetooth");
                }
            Message msg = new Message();
            if (result)
                msg.what = 1;
            else
                msg.what = 0;
            if (stablishBluetoothConnectionHandler != null)
                stablishBluetoothConnectionHandler.sendMessage(msg);
        }
    }

    /**
     * Thread that contiuosly read data from a bluetooth socket
     */
    private class ConnectedThread extends Thread{
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket){
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            }catch (IOException e){}
            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        @Override
        public void run(){
            //int bytes;
            while (true)
                try{
                    if (this.isInterrupted())
                        break;
                    int bytesAvailable = mmInStream.available();
                    if(bytesAvailable > 0){
                        byte[] buffer = new byte[bytesAvailable];
                        int bytes = mmInStream.read(buffer);
                        String readMessage = new String(buffer, 0, bytes);
                        if (!readMessage.isEmpty()){
                            if (bluetoothIn != null)
                                bluetoothIn.obtainMessage(handlerState, bytes, -1, readMessage).sendToTarget();
                        }
                    }
                }catch (IOException e){
                    break;
                }
        }

        public void write(String input){
            byte[] bytes = input.getBytes();
            try {
                mmOutStream.write(bytes);
            }catch (IOException e){}
        }

        public void cancel(){
            try{
                mmSocket.close();
            }catch (IOException e){

            }
        }
    }
}
