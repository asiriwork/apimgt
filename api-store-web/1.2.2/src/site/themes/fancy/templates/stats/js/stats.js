var t_on = {
    'apiChart':1,
    'subsChart':1,
    'serviceTimeChart':1,
    'tempLoadingSpace':1
};
var currentLocation;

var chartColorScheme1 = ["#3da0ea","#bacf0b","#e7912a","#4ec9ce","#f377ab","#ec7337","#bacf0b","#f377ab","#3da0ea","#e7912a","#bacf0b"];
//fault colors || shades of red
var chartColorScheme2 = ["#ED2939","#E0115F","#E62020","#F2003C","#ED1C24","#CE2029","#B31B1B","#990000","#800000","#B22222","#DA2C43"];
//fault colors || shades of blue
var chartColorScheme3 = ["#0099CC","#436EEE","#82CFFD","#33A1C9","#8DB6CD","#60AFFE","#7AA9DD","#104E8B","#7EB6FF","#4981CE","#2E37FE"];
currentLocation=window.location.pathname;

require(["dojo/dom", "dojo/domReady!"], function(dom){
    currentLocation=window.location.pathname;
    //Initiating the fake progress bar
    jagg.fillProgress('apiChart');jagg.fillProgress('subsChart');jagg.fillProgress('serviceTimeChart');jagg.fillProgress('tempLoadingSpace');

    jagg.post("/site/blocks/stats/ajax/stats.jag", { action:"getFirstAccessTime",currentLocation:currentLocation  },
        function (json) {            

            if (!json.error) {

                if( json.usage && json.usage.length > 0){
                    
                    var d = new Date();
                    var firstAccessDay = new Date(json.usage[0].year, json.usage[0].month-1, json.usage[0].day);
                    var currentDay = new Date(d.getFullYear(), d.getMonth(), d.getDate());
                    if(firstAccessDay.valueOf() == currentDay.valueOf()){
                        currentDay = new Date(d.getFullYear(), d.getMonth(), d.getDate()+1);
                    }
                    var rangeSlider =  $("#rangeSlider");
                    //console.info(currentDay);
                    rangeSlider.dateRangeSlider({
                        "bounds":{
                            min: firstAccessDay,
                            max: currentDay
                        },
                        "defaultValues":{
                            min: firstAccessDay,
                            max: currentDay
                        }
                    });
                    rangeSlider.bind("valuesChanged", function(e, data){
                        var from = convertTimeString(data.values.min);
                        var to = convertTimeStringPlusDay(data.values.max);

                        drawAppAPIUsage(from,to);
                        drawProviderAPIUsage(from,to);

                        
                    });
                    var width = $("#rangeSliderWrapper").width();
                    $("#rangeSliderWrapper").affix();
                    $("#rangeSliderWrapper").width(width);

                }
                else{
                    $('#middle').html("");
                    $('#middle').append($('<div class="errorWrapper"><span class="label top-level-warning"><i class="icon-warning-sign icon-white"></i>'
                        +i18n.t('errorMsgs.checkBAMConnectivity')+'</span><br/><img src="../themes/default/templates/stats/images/statsThumb.png" alt="Smiley face"></div>'));
                }


            }
            else {
                if (json.message == "AuthenticateError") {
                    jagg.showLogin();
                } else {
                    jagg.message({content:json.message,type:"error"});
                }
            }
            t_on['apiChart'] = 0;
        }, "json");

});


var drawAppAPIUsage = function(from,to){

    var fromDate = from;
    var toDate = to;
    jagg.post("/site/blocks/stats/ajax/stats.jag", { action:"getProviderAPIUsage",currentLocation:currentLocation,fromDate:fromDate,toDate:toDate  },
        function (json) {
            if (!json.error) {
                $('#lastAccessTable').find("tr:gt(0)").remove();
                var length = json.usage.length;
                $('#lastAccessTable').show();
                for (var i = 0; i < json.usage.length; i++) {
                    $('#lastAccessTable').append($('<tr><td>' + json.usage[i].appName + '</td><td>' + json.usage[i].apiName + '</td><td class="tdNumberCell">' + json.usage[i].count + '</td></tr>'));
                }
                if (length == 0) {
                    $('#lastAccessTable').hide();
                    $('#tempLoadingSpace').html('');
                    $('#tempLoadingSpace').append($('<span class="label label-info">'+i18n.t('errorMsgs.noData')+'</span>'));

                }else{
                    $('#tempLoadingSpace').hide();
                }

            } else {
                if (json.message == "AuthenticateError") {
                    jagg.showLogin();
                } else {
                    jagg.message({content:json.message,type:"error"});
                }
            }
            t_on['tempLoadingSpace'] = 0;
        }, "json");
}



var drawProviderAPIUsage = function(from,to){

    var fromDate = from;
    var toDate = to;
    jagg.post("/site/blocks/stats/ajax/stats.jag", { action:"getProviderAPIUsage",currentLocation:currentLocation,fromDate:fromDate,toDate:toDate  },
        function (json) {
            if (!json.error) {
                var length = json.usage.length,data = [];
                $('#apiChart').empty();
                $('#apiTable').find("tr:gt(0)").remove();
                for (var i = 0; i < length; i++) {
                    data[i] = [json.usage[i].apiName, parseInt(json.usage[i].count)];
                    $('#apiTable').append($('<tr><td>' + json.usage[i].apiName + '</td><td class="tdNumberCell">' + json.usage[i].count + '</td></tr>'));

                }

                if (length > 0) {
                    $('#apiTable').show();
                    require([
                        // Require the basic chart class
                        "dojox/charting/Chart",

                        // Require the theme of our choosing
                        "dojox/charting/themes/Claro",

                        // Charting plugins:

                        //  We want to plot a Pie chart
                        "dojox/charting/plot2d/Pie",

                        // Retrieve the Legend, Tooltip, and MoveSlice classes  
                        "dojox/charting/action2d/Tooltip",
                        "dojox/charting/action2d/MoveSlice",

                        //  We want to use Markers
                        "dojox/charting/plot2d/Markers",

                        //  We'll use default x/y axes
                        "dojox/charting/axis2d/Default"
                    ], function(Chart, theme, Pie, Tooltip, MoveSlice) {                                             

                        // Create the chart within it's "holding" node
                        var apiUsageChart = new Chart("apiChart");

                        // Set the theme
                        apiUsageChart.setTheme(theme);

                        // Add the only/default plot
                        apiUsageChart.addPlot("default", {
                            type: Pie,
                            markers: true,
                            radius:130
                        });

                        // Add axes
                        apiUsageChart.addAxis("x");
                        apiUsageChart.addAxis("y", { min: 5000, max: 30000, vertical: true, fixLower: "major", fixUpper: "major" });

                        // Define the data
                        var chartData; var color = -1;
                        require(["dojo/_base/array"], function(array){
                            chartData= array.map(data, function(d){
                                color++;
                                return {y: d[1], tooltip: "<b>"+d[0]+"</b><br /><i>"+d[1]+" call(s)</i>",fill:chartColorScheme1[color]};

                            });
                        });

                        //apiUsageChart.addSeries("API Usage",chartData);
                        apiUsageChart.addSeries("API Usage", [1, 2, 0.5, 1.5, 1, 2.8, 0.4])
                        //.addSeries("Series B", [2.6, 1.8, 2, 1, 1.4, 0.7, 2])
                        //.addSeries("Series C", [6.3, 1.8, 3, 0.5, 4.4, 2.7, 2])


                        // Create the tooltip
                        var tip = new Tooltip(apiUsageChart,"default");

                        // Create the slice mover
                        var mag = new MoveSlice(apiUsageChart,"default");

                        // Render the chart!
                        apiUsageChart.render();

                    });

                } else {
                    $('#apiTable').hide();
                    $('#apiChart').css("fontSize", 14);
                    $('#apiChart').append($('<span class="label label-info">'+i18n.t('errorMsgs.noData')+'</span>'));
                }


            } else {
                if (json.message == "AuthenticateError") {
                    jagg.showLogin();
                } else {
                    jagg.message({content:json.message,type:"error"});
                }
            }
            t_on['apiChart'] = 0;
        }, "json");
}


var convertTimeString = function(date){
    var d = new Date(date);
    var formattedDate = d.getFullYear() + "-" + formatTimeChunk((d.getMonth()+1)) + "-" + formatTimeChunk(d.getDate());
    return formattedDate;
};

var convertTimeStringPlusDay = function(date){
    var d = new Date(date);
    var formattedDate = d.getFullYear() + "-" + formatTimeChunk((d.getMonth()+1)) + "-" + formatTimeChunk(d.getDate()+1);
    return formattedDate;
};

var formatTimeChunk = function (t){
    if (t<10){
        t="0" + t;
    }
    return t;
};
