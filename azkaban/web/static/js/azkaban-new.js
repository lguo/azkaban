$(document).ready(function() {
  $("input").click(function(e) {
    // don't let the form submit yet
//    e.preventDefault();

    // hijacking form submit to implement the delete
    var folderName = e.target.name;
    var previousAction = $("#runSubmit").attr("action");

    $("#runSubmit").attr("action", "?action=delete&folder=" + folderName + "&toCheck=true");
  });
});