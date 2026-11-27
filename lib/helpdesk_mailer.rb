#
# With Rails 3 mail is send with the mail method. Sadly redmine
# uses this method-name too in their mailer. This is the reason
# why we need our own Mailer class.
#
class HelpdeskMailer < ActionMailer::Base
  helper :application

  include Redmine::I18n
  include MacroExpander

  # set the hostname for url_for helper
  def self.default_url_options
    { :host => Setting.host_name, :protocol => Setting.protocol }
  end

  # Sending email notifications to the supportclient
  def email_to_supportclient(issue, params)
    # issue, recipient, journal=nil, text='', copy_to=nil

    recipient = params[:recipient]
    journal = params[:journal]
    text = params[:text]
    carbon_copy = params[:carbon_copy]
    first_reply = params[:first_reply]

    redmine_headers 'Project' => issue.project.identifier,
                    'Issue-Id' => issue.id,
                    'Issue-Author' => issue.author.login
    redmine_headers 'Issue-Assignee' => issue.assigned_to.login if issue.assigned_to
    message_id issue
    references issue

    subject = "[#{issue.project.name} - ##{issue.id}] #{issue.subject}"
    # Set 'from' email-address to 'helpdesk-sender-email' if available.
    # Falls back to regular redmine behaviour if 'sender' is empty.
    p = issue.project
    s = CustomField.find_by_name('helpdesk-sender-email')
    sender = p.custom_value_for(s).try(:value) if p.present? && s.present?
    # If a custom field with text for the first reply is
    # available then use this one instead of the regular
    r = CustomField.find_by_name('helpdesk-first-reply')
    f = CustomField.find_by_name('helpdesk-email-footer')
    reply  = p.nil? || r.nil? ? '' : p.custom_value_for(r).try(:value)
    footer = p.nil? || f.nil? ? '' : p.custom_value_for(f).try(:value)
    # add carbon copy
    ct = CustomField.find_by_name('copy-to')
    if carbon_copy.nil?
      carbon_copy = issue.custom_value_for(ct).try(:value)
    end
    # attachments added with this journal (attached further down, once we
    # know which ones are already shown inline as screenshots)
    sent_attachments = []
    if journal.present? && text.present?
      journal.details.each do |d|
        next unless d.property == 'attachment' && d.value.present?
        a = Attachment.find_by(:id => d.prop_key)
        sent_attachments << a unless a.nil?
      end
    end
    if @message_id_object
      headers[:message_id] = "<#{self.class.message_id_for(@message_id_object)}>"
    end
    if @references_objects
      headers[:references] = @references_objects.collect {|o| "<#{self.class.references_for(o)}>"}.join(' ')
    end
    # create mail object to deliver
    mail = if text.present? || first_reply
      # sending out the journal note to the support client
      # or the first reply message
      reply = default_first_reply(issue) if first_reply && reply.blank?
      t = if text.present?
        "#{text}\n\n#{footer}"
      elsif footer.present?
        "#{reply}\n\n#{footer}"
      else
        reply
      end
      body = expand_macros(t, issue, journal)

      # precess reply-separator
      f = CustomField.find_by_name('helpdesk-reply-separator')
      reply_separator = issue.project.custom_value_for(f).try(:value)
      if !reply_separator.blank?
        body = reply_separator + "\n\n" + body
      end

      # screenshots in the text go inline, so they show in the body
      html_body, inline_ids = inline_images_html(issue, body)

      # attach the other files (screenshots already inline are not repeated)
      other_attachments = sent_attachments.reject { |a| inline_ids.include?(a.id) }
      other_attachments.each do |a|
        begin
          attachments[a.filename] = {
            :mime_type => a.content_type.presence || 'application/octet-stream',
            :content   => File.binread(a.diskfile)
          }
        rescue => e
          Rails.logger.error "Helpdesk: could not attach #{a.filename} (##{a.id}) to supportclient email: #{e.message}"
        end
      end

      # add download links, so the files are reachable even if the
      # delivery method drops the mail attachments
      if other_attachments.any?
        links = other_attachments.map do |a|
          url = helpdesk_attachment_download_url(
            :id => a.id,
            :token => HelpdeskAttachmentLink.token_for(a),
            :filename => a.filename
          )
          [a.filename, url]
        end
        label = l(:label_attachment_plural)
        body = "#{body}\n\n#{label}:\n" + links.map { |name, url| "- #{name}: #{url}" }.join("\n")
        if html_body
          items = links.map do |name, url|
            %(<li><a href="#{ERB::Util.h(url)}">#{ERB::Util.h(name)}</a></li>)
          end
          html_body = "#{html_body}\n<p>#{ERB::Util.h(label)}:</p>\n<ul>#{items.join}</ul>"
        end
      end

      mail_headers = {
        :from     => sender.present? && sender || Setting.mail_from,
        :reply_to => sender.present? && sender || Setting.mail_from,
        :to       => recipient,
        :subject  => subject,
        :date     => Time.zone.now,
        :cc       => carbon_copy
      }
      if html_body
        # send an html part as well, so the screenshots show in the body
        mail(mail_headers) do |format|
          format.text { render :plain => body }
          format.html { render :html => html_body.html_safe }
        end
      else
        mail(mail_headers.merge(:body => body))
      end
    else
      # fallback to a regular notifications email with redmine view
      @issue = issue
      @journal = journal
      @issue_url = url_for(:controller => 'issues', :action => 'show', :id => issue)
      mail(
        :from     => sender.present? && sender || Setting.mail_from,
        :reply_to => sender.present? && sender || Setting.mail_from,
        :to       => recipient,
        :subject  => subject,
        :date     => Time.zone.now,
        :template_path => 'mailer',
        :template_name => 'issue_edit',
        :cc            => carbon_copy
      )
    end
    # return mail object to deliver it
    return mail
  end

  private

  # Appends a Redmine header field (name is prepended with 'X-Redmine-')
  def redmine_headers(h)
    h.each { |k,v| headers["X-Redmine-#{k}"] = v.to_s }
  end

  def self.token_for(object, rand=true)
    timestamp = object.send(object.respond_to?(:created_on) ? :created_on : :updated_on)
    hash = [
      "redmine",
      "#{object.class.name.demodulize.underscore}-#{object.id}",
      timestamp.strftime("%Y%m%d%H%M%S")
    ]
    if rand
      hash << Redmine::Utils.random_hex(8)
    end
    host = Setting.mail_from.to_s.strip.gsub(%r{^.*@|>}, '')
    host = "#{::Socket.gethostname}.redmine" if host.empty?
    "#{hash.join('.')}@#{host}"
  end

  # Returns a Message-Id for the given object
  def self.message_id_for(object)
    token_for(object, true)
  end

  # Returns a uniq token for a given object referenced by all notifications
  # related to this object
  def self.references_for(object)
    token_for(object, false)
  end

  def message_id(object)
    @message_id_object = object
  end

  def references(object)
    @references_objects ||= []
    @references_objects << object
  end

  # Renders +text+ with the configured text formatting and embeds the
  # screenshots it shows as inline attachments. Handles !image.png!,
  # ![](image.png), full /attachments/download/.. URLs and raw <img> tags
  # (as written by the WYSIWYG editor).
  # Returns [html, embedded attachment ids], or [nil, []] when there is no
  # screenshot to embed (the mail then stays plain text, as before).
  def inline_images_html(issue, text)
    return [nil, []] if Setting.plain_text_mail?

    html = Redmine::WikiFormatting.to_html(Setting.text_formatting, text)
    cids = {}
    html = HelpdeskInlineImages.rewrite_images(html, issue: issue) do |a|
      cids[a.id] ||= begin
        name = attachments[a.filename] ? "#{a.id}_#{a.filename}" : a.filename
        attachments.inline[name] = File.binread(a.diskfile)
        attachments[name].url
      end
    end
    cids.empty? ? [nil, []] : [html, cids.keys]
  rescue => e
    Rails.logger.error "Helpdesk: could not embed inline images: #{e.class}: #{e.message}"
    [nil, []]
  end

  def default_first_reply(issue)
    "Your case ##issue-id## has been created."
  end
end
