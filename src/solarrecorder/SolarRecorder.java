/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package solarrecorder;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.Time;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import org.apache.commons.io.FileUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import com.luckycatlabs.sunrisesunset.Zenith;
import com.luckycatlabs.sunrisesunset.calculator.SolarEventCalculator;
import com.luckycatlabs.sunrisesunset.dto.Location;
import java.text.SimpleDateFormat;

/**
 *
 * @author colin
 */
public class SolarRecorder {
    private ArrayList envoyData;
    private final double latitude = 52.213563;
    private final double longitude = -0.0745226;
    private final Zenith zen = Zenith.OFFICIAL;
    private final int intervalError = 2;
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    
    private void getProdData() throws IOException {
        org.jsoup.nodes.Document doc = Jsoup.connect("http://envoy/production").get();

        Element h1 = doc.getElementsByTag("h1").first();
        Element table = h1.nextElementSibling();
        Elements alltr = table.getElementsByTag("tbody").first().getElementsByTag("tr");
        for (Element tr : alltr) {
            Elements alltd = tr.getElementsByTag("td");

            if (alltd.size() == 2) {
                String name = alltd.first().text();
                String value = alltd.last().text();
                switch (name) {
                    case "Currently":
                    case "Today":
                        envoyData.add(new EnvoyData(name, value));
                        break;
                }
            }
        }
    }

    private void getSysData() throws IOException {
        org.jsoup.nodes.Document doc = Jsoup.connect("http://envoy").get();

        Elements allh2 = doc.getElementsByTag("h2");
        for (Element h2 : allh2) {
            if (h2.text().equals("System Statistics")) {
                Elements tables = h2.parent().getElementsByTag("table");
                Elements alltr = tables.first().getElementsByTag("tbody").first().getElementsByTag("tr");
                for (Element tr : alltr) {
                    Elements alltd = tr.getElementsByTag("td");
                    String name = alltd.first().text();
                    String value = alltd.last().text();
                    if (name.equals("Number of Microinverters Online")) {
                        envoyData.add(new EnvoyData(name, value));
                    }
                }
            }
        }
    }

    private void getData() throws IOException {
        envoyData = new ArrayList();
        getProdData();
        getSysData();
    }
    
    private double extractCurrent(String val) {
        if (val.indexOf("kW") > 0) {
            return Double.parseDouble(val.substring(0, val.lastIndexOf(" "))) * 1000.0;
        } else {
            return Double.parseDouble(val.substring(0, val.lastIndexOf(" ")));
        }
    }
    
    private double extractToday(String val) {
        if (val.indexOf("kWh") > 0) {
            return Double.parseDouble(val.substring(0, val.lastIndexOf(" ")));
        } else {
            return Double.parseDouble(val.substring(0, val.lastIndexOf(" "))) / 1000.0;
        }
    }
    
    private void sendSolarUpdate() {
        String dbString = "jdbc:mysql://localhost:3306/Solar";

        try {
            Connection con = DriverManager.getConnection(dbString, "colin", "Quackquack1");
            Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
            String SQL = "SELECT * FROM Production";
            ResultSet rs = stmt.executeQuery(SQL);
            rs.moveToInsertRow();
            
            getData();
            
            java.util.Date now = new java.util.Date();
            rs.updateDate("Day", new Date(now.getTime()));
            rs.updateTime("Time", new Time(now.getTime()));

            for (Object evp : envoyData) {
                switch (((EnvoyData) evp).getName()) {
                case "Currently":
                    rs.updateDouble("Current", extractCurrent(((EnvoyData) evp).getValue()));
                    break;
                case "Today":
                    rs.updateDouble("Today", extractToday(((EnvoyData) evp).getValue()));
                    break;
                case "Number of Microinverters Online":
                    rs.updateInt("Inverters", Integer.parseInt(((EnvoyData) evp).getValue()));
                    break;
                }
            }
     
            rs.insertRow();
            
            stmt.close();
            rs.close();
        } catch(Exception ex) {
            ex.printStackTrace();
        }
    }
    
    private void printCalendar(Calendar c, String s) {
        System.out.println(sdf.format(c.getTime()) + s);
    }

    private void printCalendar(String s, Calendar c) {
        System.out.println(s + sdf.format(c.getTime()));
    }

    private long diffInMins(Calendar c1, Calendar c2) {
        return (c1.getTimeInMillis() - c2.getTimeInMillis()) / 1000 / 60;
    }
    
    SolarRecorder(long interval) {
        SolarEventCalculator sec = new SolarEventCalculator(new Location(latitude, longitude), "Europe/London");
        Calendar sunRise = sec.computeSunriseCalendar(zen, new GregorianCalendar());
        Calendar sunSet = sec.computeSunsetCalendar(zen, new GregorianCalendar());

        printCalendar("sunRise ", sunRise);
        printCalendar("sunSet ", sunSet);

        Calendar now = Calendar.getInstance();
        printCalendar("Now ", now);

        if (now.before(sunRise)) {
            Calendar startOfDay = Calendar.getInstance();
            startOfDay.set(Calendar.HOUR_OF_DAY, 0);
            startOfDay.set(Calendar.MINUTE, 0);
            startOfDay.set(Calendar.SECOND, 0);
            printCalendar("Start of Day ", startOfDay);

            printCalendar(now, " Before sunrise");
            if (diffInMins(sunRise, now) < interval) {
                printCalendar("Sun will rise at ", sunRise);
            } else if (diffInMins(now, startOfDay) < interval + intervalError) {
                sendSolarUpdate();
            }
        } else if (now.after(sunSet)) {
            Calendar midnight = Calendar.getInstance();
            midnight.set(Calendar.HOUR_OF_DAY, 23);
            midnight.set(Calendar.MINUTE, 59);
            midnight.set(Calendar.SECOND, 59);
            printCalendar("End of Day ", midnight);

            printCalendar(now, " After sunset");
            if (diffInMins(now, sunSet) < interval) {
                printCalendar("Sun set at ", sunSet);
                sendSolarUpdate();
            } else if (diffInMins(midnight, now) < interval + intervalError) {
                sendSolarUpdate();
            }
        } else {
            printCalendar(now, " Sun is up");
            sendSolarUpdate();
        }
    }

    public static void main(String[] args) {
        File lockFile = new File(System.getProperty("user.home") + File.separator + ".solarRecorder");
        int attempts = 5;
        while (lockFile.exists() && attempts-- >= 0) {
            try {
                Thread.sleep(2000);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        if (attempts < 0) {
            System.exit(-1);
        }

        try {
            lockFile.createNewFile();
            FileUtils.forceDeleteOnExit(lockFile);

            long interval = 15;
            if (args.length > 1) {
                interval = Long.parseLong(args[1]);
            }

            String tempDir = "/tmp";
            if (System.getProperty("os.name").contains("Windows")) {
                tempDir = "C:\\temp";
            }

            new SolarRecorder(interval);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }  
}
