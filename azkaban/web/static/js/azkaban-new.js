jQuery(function ($) {

  // listen for click on the delete button
  $(".delete").click(function (e) {
    // don't let the form submit we're going to use AJAX
    e.preventDefault();
    handleDelete(e);
  });
});

function addMessage(id, msg, className) {
  $(id).html(msg);
  $(id).addClass(className);
}

function handleDelete(e) {
    // hijacking form submit to implement the delete
    var folderName = e.target.name;
    var action = $("#runSubmit").attr("action");
    action = action + "?action=delete&folder=" + folderName;

    $.ajax({
      url: action + "&toCheck=true",
      success: function(data) {
        var obj = data;
        if ( obj.status === 'success' ) {
          addMessage('#messaging', obj.message, "success");
          // remove the node from the dom
          $('#' + folderName + '-row').remove();
        } else if ( obj.status === 'confirm' ) {
          confirm(obj.message, function () {			      
            $.ajax({
                url: action + "&toCheck=false",
                success: function(data) {
                    var obj = data;
                    if ( obj.status === 'success' ) {
                        addMessage('#messaging', obj.message, "success");
                        // remove the node from the dom
                        $('#' + folderName + '-row').remove();
                    } else if ( obj.status === 'error' ) {
                        addMessage('#messaging', obj.message, "error");
                    }
                }
            });
		      });
        } else if ( obj.status === 'error' ) {
          addMessage('#messaging', obj.message, "error");
        }
      }
    });
}

function confirm(message, callback) {
	$('#confirm').modal({
		closeHTML: "<a href='#' title='Close' class='modal-close'>x</a>",
		position: ["20%",],
		overlayId: 'confirm-overlay',
		containerId: 'confirm-container', 
		onShow: function (dialog) {
			var modal = this;

			$('.message', dialog.data[0]).append(message);

			// if the user clicks "yes"
			$('.yes', dialog.data[0]).click(function () {
				// call the callback
				if ($.isFunction(callback)) {
					callback.apply();
				}
				// close the dialog
				modal.close(); // or $.modal.close();
			});
		}
	});
}
