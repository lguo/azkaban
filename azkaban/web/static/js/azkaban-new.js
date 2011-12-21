jQuery(function ($) {

  var current_tab = 'all';

  // navigating between all/scheduled/executing
  $("#nav a").click(function (e) {
    var chunks = e.target.href.split('#');
    if ( chunks.length === 2) {
        if ( chunks[1] === current_tab ) { 
          e.preventDefault();
        }
        else {
          $('#' + current_tab + '-jobs-content').hide();
          $('#' + current_tab + '-jobs-tab').removeClass('selected');
          current_tab = chunks[1];
          $('#' + current_tab + '-jobs-content').show();
          $('#' + current_tab + '-jobs-tab').addClass('selected');
        }
    }
  });

  // listen for click on the upload-job button
  $(".upload").click(function (e) {
    // don't let the form submit we're going to use AJAX
    e.preventDefault();
    handleUpload(e);
  });

  // listen for click on the delete button
  $(".delete").click(function (e) {
    // don't let the form submit we're going to use AJAX
    e.preventDefault();
    handleDelete(e);
  });

  // listen for click on the schedule link
  $(".schedule").click(function (e) {
    e.preventDefault();
    handleSchedule(e);
  });

});

function addMessage(id, msg, className) {
  $(id).html(msg);
  $(id).addClass(className);
}

function handleUpload(e) {
  $('#upload-job').modal({
		closeHTML: "<a href='#' title='Close' class='modal-close'>x</a>",
		position: ["20%",],
		containerId: 'confirm-container',
    containerCss: {
      'height': '220px',
      'width': '565px'
    },
		onShow: function (dialog) {
			var modal = this;

			// if the user clicks "yes"
			$('.yes', dialog.data[0]).click(function () {
				// close the dialog
				modal.close(); // or $.modal.close();
			});
		}
	});
}

function handleSchedule(e) {
  $('#schedule-job').modal({
		closeHTML: "<a href='#' title='Close' class='modal-close'>x</a>",
		position: ["20%",],
		//containerId: 'confirm-container',
    containerCss: {
      'height': '220px',
      'width': '565px'
    },
		onShow: function (dialog) {
			var modal = this,
          selected_job,
          date,
          hour,
          minutes,
          am_pm,
          period,
          period_units,
          data;

      // insert the job name into the modal
      selected_job = $(e.target).attr('data-job-name');
      $('#schedule-job-name').html(selected_job);

			// if the user clicks "yes"
			$('.yes', dialog.data[0]).click(function () {

        //Get the data from all the fields
        date = $('input[name=date]');
        hour = $('input[name=hour]');
        minutes = $('input[name=minutes]');
        am_pm = $('select[name=am_pm]');
        period = $('input[name=period]');
        period_units = $('select[name=period_units]');

        data = 'action=schedule' + '&date=' + date.val() + '&hour=' + hour.val() + '&minutes='
        + minutes.val() + 'am_pm=' + am_pm.val() + '&period=' + period.val() + '&period_units='
        + period_units.val() + '&jobs=' + selected_job;

				// submit the form
        $.ajax({
            url: "/",
            type: "POST",        
            data: data,
            cache: false,
             
            success: function (html) {     
                //if process.php returned 1/true (send mail success)
                
                    //show the success message
//                    $('.done').fadeIn('slow');
                addMessage('.messaging', selected_job + ' was scheduled', "success");
                     
                //if process.php returned 0/false (send mail failed)
              //  } else alert('Sorry, unexpected error. Please try again later.');              
            }      
        });
			  
			  // close the dialog
			  modal.close(); // or $.modal.close();
			});
		}
	});
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
	$('#confirm-delete').modal({
		closeHTML: "<a href='#' title='Close' class='modal-close'>x</a>",
		position: ["20%",],
		containerId: 'confirm-container',
    containerCss: {
      'height': '220px',
      'width': '565px'
    },
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
